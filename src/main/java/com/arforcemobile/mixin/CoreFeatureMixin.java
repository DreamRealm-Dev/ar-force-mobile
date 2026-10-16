package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.github.argon4w.acceleratedrendering.configs.FeatureStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 手机环境下的 AR 配置热注入：
 * - 有 Sodium/Embeddium 时，把 force_translucent_acceleration 热置为 DISABLED；
 * - 无条件把 debug_context 热置为 DISABLED；
 * - 关闭 GUI 加速时把 shouldByPassGuiBatching 热置为 true。
 *
 * 只改运行期返回值，不改 AR 的配置文件；桌面环境完全不拦截。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.CoreFeature")
public class CoreFeatureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean translucentLogged;
    private static boolean debugLogged;
    private static boolean guiBypassLogged;

    @Inject(method = "getForceTranslucentAccelerationSetting", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableForceTranslucent(CallbackInfoReturnable<FeatureStatus> cir) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        if (!ARForceConfig.disableTranslucentAcceleration()) {
            return;
        }
        if (!ARForceEnvironment.isSodiumLikeActive()) {
            return;
        }
        if (!translucentLogged) {
            translucentLogged = true;
            LOGGER.info("[ARForceMobile] Hot-inject force_translucent_acceleration=DISABLED (Sodium-like renderer detected)");
        }
        cir.setReturnValue(FeatureStatus.DISABLED);
    }

    @Inject(method = "isDebugContextEnabled", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$disableDebugContext(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        if (!ARForceConfig.DISABLE_DEBUG_CONTEXT.get()) {
            return;
        }
        if (!debugLogged) {
            debugLogged = true;
            LOGGER.info("[ARForceMobile] Hot-inject debug_context=DISABLED");
        }
        cir.setReturnValue(false);
    }

    @Inject(method = "shouldByPassGuiBatching", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$bypassGuiBatching(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || ARForceEnvironment.isMGL()) {
            return;
        }
        if (!ARForceConfig.disableGuiAcceleration()) {
            return;
        }
        if (!guiBypassLogged) {
            guiBypassLogged = true;
            LOGGER.info("[ARForceMobile] Hot-inject GUI batching bypass=true (non-MGL)");
        }
        cir.setReturnValue(true);
    }
}
