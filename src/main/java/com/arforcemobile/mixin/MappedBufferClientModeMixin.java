package com.arforcemobile.mixin;

import com.arforcemobile.ARClientBufferUploader;
import com.arforcemobile.ARClientBufferView;
import com.arforcemobile.ARMapMode;
import com.github.argon4w.acceleratedrendering.core.utils.MutableSize;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MappedBuffer 的最终兜底：客户端内存 + glNamedBufferSubData 上传。
 *
 * 分层（auto 模式）：
 *   第 0 层  PERSISTENT|COHERENT 映射成功（桌面 / 8Gen1 等新驱动）→ 原生路径，零改动。
 *   第 1 层  ImmutableBufferSafeMapMixin：0xC2 映射 NULL 时去位降级重试。
 *   第 2 层  本 mixin：降级映射也返回 NULL（甚至缓冲没有存储）→ map() 返回值替换为
 *            nmemAlloc 的客户端指针，CPU 写入全部落在客户端内存；
 *            由 ARClientBufferUploader 在 prepareBuffers/drawBuffers 前增量 subData 上传。
 *            subData 是 GLES 核心路径，任何驱动都能跑 —— 保证 AR 不会因映射缺失崩溃。
 *   第 3 层  AvailabilityUtils 门禁：以上全部关闭时 AR 不可用。
 *
 * -Darforcemobile.mapMode=client 时无条件进入客户端模式（在映射正常的设备上
 * 复现第 2 层路径，用于没有 610 真机时的验证）。
 *
 * 扩容：afterExpand() 会重新调用 map()，TAIL 钩子按新的 getSize() 重新分配客户端内存。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.backends.buffers.MappedBuffer")
public abstract class MappedBufferClientModeMixin implements ARClientBufferView {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    @Shadow(remap = false)
    private long position;

    @Unique
    private boolean arf$clientMode;

    @Unique
    private long arf$clientPtr;

    @Unique
    private long arf$clientSize;

    @Unique
    private long arf$uploaded;

    @Unique
    private boolean arf$storageEnsured;

    @Unique
    private boolean arf$dirty;

    @Unique
    private static boolean arf$loggedClientMode;

    @Inject(method = "map()J", at = @At("TAIL"), cancellable = true, remap = false)
    private void arforcemobile$clientFallbackMap(CallbackInfoReturnable<Long> cir) {
        boolean force = ARMapMode.isForceClient();
        long mapped = cir.getReturnValue();
        // native 模式完全不干预；原生映射成功且非强制时走第 0 层
        if (!force && (ARMapMode.isNativeOnly() || mapped != 0L)) {
            return;
        }
        // 运行期保险：AR 已被门禁放行、而本缓冲运行期映射仍失败时，无条件进入
        // 客户端模式兜住，避免 NULL 指针解引用 SIGSEGV。client_buffer_fallback 只
        // 决定"两种映射都不可用的设备是否在门禁层就开启 AR"，不拦截这层保险。
        long size = ((MutableSize) (Object) this).getSize();
        if (size <= 0) {
            return;
        }
        if (arf$clientSize < size) {
            long ptr = arf$clientPtr == 0L
                    ? MemoryUtil.nmemAlloc(size)
                    : MemoryUtil.nmemRealloc(arf$clientPtr, size);
            if (ptr == 0L) {
                // 分配失败：保持原返回值（null 映射），由探测门禁兜底
                return;
            }
            arf$clientPtr = ptr;
            arf$clientSize = size;
        }
        arf$clientMode = true;
        arf$uploaded = 0;
        arf$storageEnsured = false;
        // register() 同时把 tier-2 生效标志置位（绘制方法安全闸据此把 BASEVERTEX 换成 INDIRECT）
        ARClientBufferUploader.register(this);
        if (!arf$loggedClientMode) {
            arf$loggedClientMode = true;
            LOGGER.info("[ARForceMobile] MappedBuffer entering client-memory mode ({}), buffer size {} bytes",
                    force ? "forced by -Darforcemobile.mapMode=client" : "GL mapping unavailable",
                    size);
        }
        cir.setReturnValue(arf$clientPtr);
    }

    @Inject(method = "reset()V", at = @At("TAIL"), remap = false)
    private void arforcemobile$onReset(CallbackInfo ci) {
        arf$uploaded = 0;
    }

    /**
     * reserve() 写入前进时标脏入队（reserve(JZ)J 是全部 reserve 的底层重载）。
     * 每次顶点写入只付一个字段比较的成本；上传器因此无需每帧全量扫描上万条
     * 注册表（1.3.5 及以前 tier-2 的主要固定开销）。
     */
    @Inject(method = "reserve(JZ)J", at = @At("TAIL"), remap = false)
    private void arforcemobile$markDirty(long bytes, boolean tracked, CallbackInfoReturnable<Long> cir) {
        if (arf$clientMode && !arf$dirty && position > arf$uploaded) {
            arf$dirty = true;
            ARClientBufferUploader.markDirty(this);
        }
    }

    @Override
    public boolean arf$isClientMode() {
        return arf$clientMode;
    }

    @Override
    public long arf$getClientPtr() {
        return arf$clientPtr;
    }

    @Override
    public long arf$getPosition() {
        return position;
    }

    @Override
    public long arf$getUploaded() {
        return arf$uploaded;
    }

    @Override
    public void arf$setUploaded(long value) {
        arf$uploaded = value;
    }

    @Override
    public long arf$getClientSize() {
        return arf$clientSize;
    }

    @Override
    public boolean arf$isStorageEnsured() {
        return arf$storageEnsured;
    }

    @Override
    public void arf$setStorageEnsured(boolean value) {
        arf$storageEnsured = value;
    }

    @Override
    public boolean arf$isDirty() {
        return arf$dirty;
    }

    @Override
    public void arf$setDirty(boolean value) {
        arf$dirty = value;
    }

    @Override
    public void arf$releaseClient() {
        if (arf$clientPtr != 0L) {
            MemoryUtil.nmemFree(arf$clientPtr);
            arf$clientPtr = 0L;
        }
        arf$clientMode = false;
        arf$clientSize = 0L;
        arf$dirty = false;
        ARClientBufferUploader.unregister(this);
    }
}
