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
 * 非 MGL 的手机翻译层上强制关闭 AR 对 ModernUI 文本/UI 的加速。
 *
 * MGL 已改用 INDIRECT，因此保留 ModernUI 加速；旧版 MG/gl4es 在 BASEVERTEX 下
 * ModernUI 会闪烁/不显示，所以继续关闭。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.features.mods.ModsFeature")
public class ModsFeatureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean logged;

    @Inject(method = "shouldAccelerateModernUI", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableModernUIAcceleration(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || ARForceEnvironment.isMGL()) {
            return;
        }
        if (!ARForceConfig.disableModernuiAcceleration()) {
            return;
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[ARForceMobile] Hot-disabled AR ModernUI acceleration (non-MGL)");
        }
        cir.setReturnValue(false);
    }
}
