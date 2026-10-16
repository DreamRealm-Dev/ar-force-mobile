package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 非 MGL 的手机翻译层上强制关闭 AR 的 GUI/容器界面加速。
 *
 * 旧版 MG/gl4es 在 BASEVERTEX 下 GUI 加速会导致 UI 不显示/闪烁；MGL 已改用 INDIRECT，
 * 因此保留 GUI 加速。`disable_item_acceleration` 仍可作为所有手机环境的 A/B 总开关。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.features.items.AcceleratedItemRenderingFeature")
public class AcceleratedItemRenderingFeatureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean logged;
    private static boolean itemLogged;

    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableItemAcceleration(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.DISABLE_ITEM_ACCELERATION.get()) {
            return;
        }
        if (!itemLogged) {
            itemLogged = true;
            LOGGER.info("[ARForceMobile] Hot-disabled AR item acceleration");
        }
        cir.setReturnValue(false);
    }

    @Inject(method = "shouldAccelerateInGui", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableGuiAcceleration(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || ARForceEnvironment.isMGL()) {
            return;
        }
        if (!ARForceConfig.disableGuiAcceleration()) {
            return;
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[ARForceMobile] Hot-disabled AR GUI acceleration (non-MGL)");
        }
        cir.setReturnValue(false);
    }

    @Inject(method = "shouldUseGuiItemBatching", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableGuiItemBatching(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || ARForceEnvironment.isMGL()) {
            return;
        }
        if (!ARForceConfig.disableGuiAcceleration()) {
            return;
        }
        cir.setReturnValue(false);
    }

    @Inject(method = "shouldMergeGuiItemBatches", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableMergeGuiItemBatches(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || ARForceEnvironment.isMGL()) {
            return;
        }
        if (!ARForceConfig.disableGuiAcceleration()) {
            return;
        }
        cir.setReturnValue(false);
    }
}
