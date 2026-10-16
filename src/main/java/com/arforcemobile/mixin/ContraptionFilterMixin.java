package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create contraption 实体强制走原版渲染管线（contraption_vanilla_pipeline，默认 true）。
 *
 * 1.3.10 独有、1.3.11~1.3.16 主线缺失的能力，1.3.17 原样移植。
 * 本类是按 1.3.10 jar 的字节码逐条还原的（javap -c：守卫式早返回 + 反射探测 +
 * 一次性日志 + setReturnValue(false)），骨架与 AvailabilityUtilsMixin /
 * AcceleratedEntityRenderingFeatureMixin 的既有风格一致。
 *
 * 原理：AR 的 FilterFeature.testEntity 决定某个实体是否走 AR 加速路径。命中 HEAD 后
 * 在手机环境 + 开关开启时直接 setReturnValue(false)（= 该实体不加速），于是 Create 的
 * contraption 实体落回原版渲染管线，交给 CreateBetterFPS（backend=off）的 Sodium fast
 * path，而不是 AR 的 accelerated buffer source。只影响 Create contraption 实体，
 * 桌面环境（ARForceEnvironment.isMobile()==false）零介入，等于完全不装这个 mixin。
 *
 * 目标类 / 方法用 remap=false：FilterFeature 是 AR 的类，不在 Minecraft 混淆映射内。
 * Create 的 AbstractContraptionEntity 用反射探测（Create 是可选的，未安装时
 * ClassNotFoundException -> contraptionClass=null -> 直接放行），只探测一次。
 */
@Mixin(
        targets = {"com.github.argon4w.acceleratedrendering.features.filter.FilterFeature"}
)
public class ContraptionFilterMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static Class<?> contraptionClass;
    private static boolean classResolved;
    private static boolean logged;

    @Inject(
            method = {"testEntity"},
            at = {@At("HEAD")},
            cancellable = true,
            remap = false
    )
    private static void arforcemobile$contraptionVanilla(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.CONTRAPTION_VANILLA_PIPELINE.get()) {
            return;
        }

        if (!classResolved) {
            classResolved = true;

            try {
                contraptionClass = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
            } catch (ClassNotFoundException e) {
                contraptionClass = null;
            }
        }

        if (contraptionClass == null) {
            return;
        }

        if (contraptionClass.isInstance(entity)) {
            if (!logged) {
                logged = true;
                LOGGER.info("[ARForceMobile] Contraption entities forced onto vanilla pipeline (CBF Sodium fast path)");
            }

            cir.setReturnValue(false);
        }
    }
}
