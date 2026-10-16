package com.arforcemobile.mixin;

import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

/**
 * LightStorage 上传计数器（纯诊断，不改变任何行为）。
 *
 * 反编译结论（Flywheel 1.0.6，javap 实证）：{@code LightStorage.upload} 首行
 * 即 {@code changed.isEmpty() → return}，脏时才全量上传整个 arena。
 * 此 mixin 只在 HEAD 记录“本次调用是否命中早退”，供 8Gen1 真机判断光照全量
 * 上传是偶发还是高频，决定增量上传是否值得做。桌面环境不计数。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.LightStorage")
public abstract class LightStorageUploadCounterMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    @Unique
    private static boolean arf$appliedLogged;

    @Shadow(remap = false)
    private BitSet changed;

    @Inject(method = "upload(Ldev/engine_room/flywheel/backend/gl/buffer/GlBuffer;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$countLightUpload(GlBuffer buffer, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        if (!arf$appliedLogged) {
            arf$appliedLogged = true;
            LOGGER.info("[ARForceMobile] LightStorageUploadCounterMixin applied (upload hook reachable)");
        }
        try {
            if (changed == null || changed.isEmpty()) {
                ARFTboTracker.onLightUploadSkip();
            } else {
                ARFTboTracker.onLightUpload();
            }
        } catch (Throwable ignored) {
            // 诊断钩子绝不干扰渲染
        }
    }
}
