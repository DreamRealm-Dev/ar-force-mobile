package com.arforcemobile;

import net.minecraft.client.renderer.RenderType;

/**
 * 标记接口：被本模组 mixin 注入过的「包裹型 RenderType」用它暴露被包裹的原始 RenderType。
 *
 * 目前只有 ImmediatelyFast 的 BatchingBuffers\$WrappedRenderLayer（见 IfWrappedRenderLayerMixin）。
 *
 * 背景（L1 报告 §1/§2 R1，H:\mod\ar-force-mobile\investigation\L-leak\L1-AR-cache.md）：
 * AR 的 AcceleratedBufferSource.activeBuilders 与 AcceleratedRingBuffers\$Buffers.builders
 * 以 LayerKey(layer, renderType) 为键，而 LayerKey 是 record、equals/hashCode 委托给 renderType；
 * IF 每次 HudBatchingBufferSource.getBuffer 都新建 wrapper（+2 个捕获 lambda），AR 注入的
 * equals/hashCode 又比较 original+startAction+endAction（lambda 引用相等）⇒ 键永不重复、
 * 两张表无界增长，并把 wrapper 长期钉住（直方图 350,897 wrapper ≈ 351,104 builder/LayerKey）。
 * 把建键用的 RenderType 规范化回 original 后，条目数被真实 RenderType 数量封顶。
 */
public interface ARFOriginalRenderType {

    /** @return 该包裹层包裹的原始 RenderType；未捕获到宿主时为 null（调用方需原样回退）。 */
    RenderType arf$original();
}
