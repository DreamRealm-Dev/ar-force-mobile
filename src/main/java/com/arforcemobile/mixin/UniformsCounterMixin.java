package com.arforcemobile.mixin;

import com.arforcemobile.ARFSubmitTracker;
import com.arforcemobile.ARForceEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P6 提交层计数：Uniforms.bindAll 每帧一次（5 个 UBO 的 glBindBufferRange
 * 集中在这里，javap 实证 ALL_BUFFERS 遍历）。量化每帧固定提交次数。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.uniform.Uniforms")
public abstract class UniformsCounterMixin {

    @Inject(method = "bindAll()V", at = @At("HEAD"), remap = false)
    private static void arforcemobile$onBindAll(CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFSubmitTracker.onBindAll();
    }
}
