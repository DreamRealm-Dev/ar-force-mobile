package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel SectionTracker 等值集合去重。
 *
 * SectionTracker.sections(LongSet) 原实现没有等值短路：每次调用都
 * new LongArraySet 拷贝一份并无条件触发全部 listener。转动的 contraption
 * 每帧都提交光照 section 集（AABB 角落每 tick 变化使 min/max 短路失效，
 * 而集合内容基本不变），造成每帧分配 + listener 风暴 +
 * ShaderLightVisualStorage/LightStorage 下游反复标记脏。
 * 集合内容不变时直接跳过，行为与原实现等价（监听方已持有同样的集合）。
 */
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.storage.SectionTracker")
public abstract class SectionTrackerDedupMixin {

    @Shadow(remap = false)
    private LongSet sections;

    @Inject(method = "sections(Lit/unimi/dsi/fastutil/longs/LongSet;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void arforcemobile$dedup(LongSet newSections, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.FLYWHEEL_LIGHT_DEDUP.get()) {
            return;
        }
        if (sections.equals(newSections)) {
            ARFTboTracker.onSectionDedup();
            if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
                ARFTboTracker.tickDiagnostics();
            }
            ci.cancel();
        }
    }
}
