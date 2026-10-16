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
 * AR 实体加速的可选总开关（默认不关闭）。
 *
 * MGL 已改用 INDIRECT 绘制方法，理论上实体加速应恢复正常；若仍异常，可设置
 * `disable_entity_acceleration=true` 强制关闭来做 A/B。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityRenderingFeature")
public class AcceleratedEntityRenderingFeatureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean logged;

    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableEntityAcceleration(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.DISABLE_ENTITY_ACCELERATION.get()) {
            return;
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[ARForceMobile] Hot-disabled AR entity acceleration");
        }
        cir.setReturnValue(false);
    }
}
