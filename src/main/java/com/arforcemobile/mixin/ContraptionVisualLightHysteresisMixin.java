package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create ContraptionVisual 的光照 section 滞回。
 *
 * 原实现(checkAndUpdateLightSections)每帧用实体 AABB 求 min/max section，
 * 与缓存相等就 return。转动的 contraption 其 AABB 角落随角度连续变化，
 * 频繁跨越 section 边界使缓存短路失效——而且 set 是 min/max 的全盒展开，
 * 与 min/max 一一对应，一旦走到重建，集合内容必然不同。因此单纯在
 * SectionTracker 层做等值去重对 Create 是数学上的死代码(真机实测 0 命中)。
 *
 * 正确修法是滞回：缓存上次提交的"外扩 1 个 section"的范围，真实范围仍在
 * 缓存范围内时直接跳过；越出才以新真实范围 ±1 重建并提交。
 * 提交的是超集，光照数值不变，仅多订阅少量边缘 section 的光照更新。
 *
 * 仅手机环境生效；桌面与开关关闭时走 Create 原实现。
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.render.ContraptionVisual")
public abstract class ContraptionVisualLightHysteresisMixin {

    @Shadow(remap = false)
    private int lightPaddingBlocks;

    @Shadow(remap = false)
    private SectionTrackedVisual.SectionCollector sectionCollector;

    /** 滞回余量：缓存范围在真实范围基础上每侧再外扩的 section 数。 */
    @Unique
    private static final int ARF_HYSTERESIS_SECTIONS = 1;

    @Unique
    private boolean arf$hasRange;

    @Unique
    private int arf$loX;
    @Unique
    private int arf$loY;
    @Unique
    private int arf$loZ;
    @Unique
    private int arf$hiX;
    @Unique
    private int arf$hiY;
    @Unique
    private int arf$hiZ;

    @Inject(method = "checkAndUpdateLightSections", at = @At("HEAD"), cancellable = true, remap = false)
    private void arforcemobile$lightHysteresis(CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.FLYWHEEL_LIGHT_DEDUP.get()) {
            return;
        }

        AABB aabb = ((ContraptionEntityAccessor) (Object) this).arf$entity().getBoundingBox();
        int minX = SectionPos.blockToSectionCoord(Mth.floor(aabb.minX) - lightPaddingBlocks);
        int minY = SectionPos.blockToSectionCoord(Mth.floor(aabb.minY) - lightPaddingBlocks);
        int minZ = SectionPos.blockToSectionCoord(Mth.floor(aabb.minZ) - lightPaddingBlocks);
        int maxX = SectionPos.blockToSectionCoord(Mth.ceil(aabb.maxX) + lightPaddingBlocks);
        int maxY = SectionPos.blockToSectionCoord(Mth.ceil(aabb.maxY) + lightPaddingBlocks);
        int maxZ = SectionPos.blockToSectionCoord(Mth.ceil(aabb.maxZ) + lightPaddingBlocks);

        if (arf$hasRange
                && minX >= arf$loX && maxX <= arf$hiX
                && minY >= arf$loY && maxY <= arf$hiY
                && minZ >= arf$loZ && maxZ <= arf$hiZ) {
            ARFTboTracker.onSectionDedup();
            if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
                ARFTboTracker.tickDiagnostics();
            }
            ci.cancel();
            return;
        }

        int loX = minX - ARF_HYSTERESIS_SECTIONS;
        int loY = minY - ARF_HYSTERESIS_SECTIONS;
        int loZ = minZ - ARF_HYSTERESIS_SECTIONS;
        int hiX = maxX + ARF_HYSTERESIS_SECTIONS;
        int hiY = maxY + ARF_HYSTERESIS_SECTIONS;
        int hiZ = maxZ + ARF_HYSTERESIS_SECTIONS;

        LongSet expanded = new LongArraySet();
        for (int x = loX; x <= hiX; x++) {
            for (int y = loY; y <= hiY; y++) {
                for (int z = loZ; z <= hiZ; z++) {
                    expanded.add(SectionPos.asLong(x, y, z));
                }
            }
        }
        sectionCollector.sections(expanded);

        arf$loX = loX;
        arf$loY = loY;
        arf$loZ = loZ;
        arf$hiX = hiX;
        arf$hiY = hiY;
        arf$hiZ = hiZ;
        arf$hasRange = true;

        ci.cancel();
    }
}
