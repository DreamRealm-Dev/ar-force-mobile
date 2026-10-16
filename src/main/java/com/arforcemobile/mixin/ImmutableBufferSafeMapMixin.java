package com.arforcemobile.mixin;

import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARMapMode;
import com.arforcemobile.ARMapProbe;
import org.lwjgl.opengl.GL46;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MappedBuffer 空映射降级回退（防止 SIGSEGV @ 0x0）—— 第 1 层。
 *
 * AR 1.0.14 的 ImmutableBuffer.map(J length, I flags) 以 WRITE|PERSISTENT|COHERENT (0xC2)
 * 调 glMapNamedBufferRange(buffer, 0, length, flags)；MobileGlues 把标志原样透传给
 * GLES 驱动，部分设备/驱动不认 PERSISTENT|COHERENT 位而返回 NULL。AR 对返回值零校验，
 * address=0 后第一次 beginTransform 写共享变换即 memPutFloat(0) 崩溃。
 *
 * 本 mixin 在首映返回 NULL 且为手机环境时，去掉 PERSISTENT|COHERENT 位、
 * 以相同的 (buffer, 0, length) 原样重试（MobileGlues 的 bufferCoherentAsFlush=1
 * 模式下映射即视为 coherent；同时 sync mixin 在 drawBuffers 前补全量 memory barrier）。
 * 重试仍失败则维持 NULL，由 MappedBufferClientModeMixin（第 2 层）接管。
 *
 * 另承担 mapMode=tier1 的强制模拟：即使持久映射成功也解除后以下降级标志重映射，
 * 用于在映射正常的设备（8Gen1）上复现 610 的 tier-1 运行路径做跑分对照。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.backends.buffers.ImmutableBuffer")
public abstract class ImmutableBufferSafeMapMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    @Shadow(remap = false)
    private int bufferHandle;

    private static boolean loggedFallback;
    private static boolean loggedForced;
    private static boolean loggedRestore;
    private static boolean loggedDanglingGuard;

    @Inject(method = "map(JI)J", at = @At("RETURN"), cancellable = true, remap = false)
    private void arforcemobile$fallbackMap(long length, int flags, CallbackInfoReturnable<Long> cir) {
        boolean forceTier1 = ARMapMode.isForceTier1();
        // 1.3.5 自动策略：普通映射可用即优先 tier-1（8Gen1 实测降级映射 107.3 分
        // 全面优于原生持久一致映射 73.4）——成功返回的持久映射也解除后重映射。
        boolean preferTier1;
        try {
            preferTier1 = !ARMapMode.isNativeOnly()
                    && !ARMapMode.isForceClient()
                    && ARMapProbe.plainMapWorks();
        } catch (Throwable t) {
            preferTier1 = false;
        }
        if (!forceTier1 && !preferTier1 && cir.getReturnValue() != 0L) {
            return;
        }
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        int persistentCoherent = GL46.GL_MAP_PERSISTENT_BIT | GL46.GL_MAP_COHERENT_BIT;
        if ((flags & persistentCoherent) == 0) {
            return;
        }
        // 1.3.24（B1 内存安全）：一旦执行了 glUnmapNamedBuffer，cir 里的旧指针就失效了，
        // 后面任何「失败就保持原返回值」的写法都会让 AR 继续用悬垂指针写顶点。这里记录
        // 是否已经解除了原映射，供失败路径决定"恢复原状"还是"交 0 给第 2 层"。
        boolean unmappedOriginal = false;
        try {
            int downgraded = flags & ~persistentCoherent;
            if ((forceTier1 || preferTier1) && cir.getReturnValue() != 0L) {
                // 每缓冲同时只允许一个映射：先解除刚成功的持久映射，再以下降级
                // 标志重映射——与 610 上"0xC2 返回 NULL 后重试"的运行时结果等价。
                GL46.glUnmapNamedBuffer(bufferHandle);
                unmappedOriginal = true;
                if (!loggedForced) {
                    loggedForced = true;
                    LOGGER.info("[ARForceMobile] Persistent mapping downgraded to plain mapping (tier-1; "
                            + (forceTier1 ? "forced by -Darforcemobile.mapMode=tier1" : "auto tier policy")
                            + ")");
                }
            }
            long retry = GL46.nglMapNamedBufferRange(bufferHandle, 0L, length, downgraded);
            if (retry != 0L) {
                if (!loggedFallback) {
                    loggedFallback = true;
                    LOGGER.info("[ARForceMobile] Persistent map returned NULL, remapped with downgraded flags "
                            + "(PERSISTENT|COHERENT stripped) -> AR can run without SIGSEGV");
                }
                cir.setReturnValue(retry);
                return;
            }
            // ---- 降级重映射失败：绝不能让 cir 留着已经解除的旧指针（B1）----
            drainGlErrors();
            if (unmappedOriginal) {
                // 先尝试按原 flags 把映射恢复回原状态：AR 的 address 仍指向一块真实映射的
                // 内存（等于这次降级没发生过，走 tier-0 原生路径）。
                long restored = GL46.nglMapNamedBufferRange(bufferHandle, 0L, length, flags);
                if (restored != 0L) {
                    if (!loggedRestore) {
                        loggedRestore = true;
                        LOGGER.info("[ARForceMobile] Downgraded remap failed; original persistent mapping restored "
                                + "(AR keeps a valid mapping, no dangling pointer)");
                    }
                    cir.setReturnValue(restored);
                    return;
                }
                drainGlErrors();
            }
            // 恢复不了：返回 0，由 MappedBufferClientModeMixin（第 2 层）接管客户端内存。
            // 返回 0 而不是旧指针是本条修复的核心——旧指针的那块映射已经被解除。
            if (!loggedDanglingGuard) {
                loggedDanglingGuard = true;
                LOGGER.warn("[ARForceMobile] Downgraded remap failed and the original mapping could not be restored "
                        + "-> returning 0 so the client-memory fallback takes over (no dangling pointer)");
            }
            cir.setReturnValue(0L);
        } catch (Throwable ignored) {
            // 异常路径同样不能留下"已解除的映射 + 旧指针"：能恢复就恢复，恢复不了交 0。
            if (unmappedOriginal) {
                try {
                    long restored = GL46.nglMapNamedBufferRange(bufferHandle, 0L, length, flags);
                    cir.setReturnValue(restored != 0L ? restored : 0L);
                } catch (Throwable ignored2) {
                    cir.setReturnValue(0L);
                }
            }
        }
    }

    /**
     * 清掉失败重试留下的 GL 错误状态，避免污染后续 GL 调用的错误标志。
     *
     * 1.3.26 修正（MG 源码理论验证 task-20）：MG 的 glGetError **永远返回 GL_NO_ERROR**
     * （getter.cpp:183-209），`while (glGetError() != GL_NO_ERROR)` 只跑一轮、漏排队的第二个错误。
     * 改成固定 4 次有界调用。
     */
    private static void drainGlErrors() {
        for (int i = 0; i < 4; i++) {
            GL46.glGetError();
        }
    }
}
