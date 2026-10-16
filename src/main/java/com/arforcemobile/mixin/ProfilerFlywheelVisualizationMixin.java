package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：Flywheel 三个阶段（flywheel / flywheel_frame / flywheel_crumble）。
 *
 * 目标方法都是 private（H-profiler §3.3 的 javap 证据）：只看名字注入即可，
 * 写错描述符会静默失效 ⇒ 由 PROFILE 行的 hooks= 位图发现。
 * Flywheel 是编译期依赖（libs/flywheel-neoforge-1.21.1-1.0.6.jar），但运行时由 Create 内嵌提供。
 */
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl")
public class ProfilerFlywheelVisualizationMixin {

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void arforcemobile$flywheelHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_FLYWHEEL, ARFProfile.P_FLYWHEEL);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void arforcemobile$flywheelTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_FLYWHEEL, ARFProfile.P_FLYWHEEL);
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), remap = false)
    private void arforcemobile$flywheelFrameHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_FLYWHEEL_FRAME, ARFProfile.P_FLYWHEEL_FRAME);
    }

    @Inject(method = "beginFrame", at = @At("RETURN"), remap = false)
    private void arforcemobile$flywheelFrameTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_FLYWHEEL_FRAME, ARFProfile.P_FLYWHEEL_FRAME);
    }

    @Inject(method = "renderCrumbling", at = @At("HEAD"), remap = false)
    private void arforcemobile$flywheelCrumbleHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_FLYWHEEL_CRUMBLE, ARFProfile.P_FLYWHEEL_CRUMBLE);
    }

    @Inject(method = "renderCrumbling", at = @At("RETURN"), remap = false)
    private void arforcemobile$flywheelCrumbleTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_FLYWHEEL_CRUMBLE, ARFProfile.P_FLYWHEEL_CRUMBLE);
    }
}
