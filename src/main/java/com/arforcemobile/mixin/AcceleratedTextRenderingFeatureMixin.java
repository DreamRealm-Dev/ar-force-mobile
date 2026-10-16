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
 * 非 MGL 的手机翻译层（旧版 MG/gl4es 等）上强制关闭 AR 的 BakedGlyph 文本加速。
 *
 * 这些环境在 BASEVERTEX 下文本会闪烁/不显示；MGL 已改用 INDIRECT，因此保留 AR 文本加速。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.features.text.AcceleratedTextRenderingFeature")
public class AcceleratedTextRenderingFeatureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean logged;

    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableTextAcceleration(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || ARForceEnvironment.isMGL()) {
            return;
        }
        if (!ARForceConfig.disableTextAcceleration()) {
            return;
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[ARForceMobile] Hot-disabled AR text acceleration (non-MGL)");
        }
        cir.setReturnValue(false);
    }
}
