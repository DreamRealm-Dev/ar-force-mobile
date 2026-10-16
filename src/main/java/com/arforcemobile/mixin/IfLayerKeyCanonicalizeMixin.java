package com.arforcemobile.mixin;

import com.arforcemobile.ARFIfLeakState;
import com.arforcemobile.ARFOriginalRenderType;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * L3 泄漏修复（L1 §2 R1 第 2 步）：在 AR 唯一的造键点做键规范化。
 *
 * 事实（L1 §1 E2，javap 指令偏移）：AcceleratedBufferSource#getBuffer 开头
 *   偏移 0-10    new LayerKey(layer, renderType)                <-- 唯一的键构造点
 *   偏移 12-26   activeBuilders.get(key)
 *   偏移 328-339 currentBuffer.builders.put(key, builder)      <-- 孪生表
 *   偏移 356-369 activeBuilders.put(key, builder)
 * 这个 LayerKey 局部变量同时用于 map.get / map.put / new AcceleratedBufferBuilder(...) 的第 6 个参数，
 * 因此只改写 new LayerKey(...) 的 RenderType 参数一处，即可覆盖全部三处。
 *
 * descriptor 取自 LayerKey 构造器签名：<init>(ILnet/minecraft/client/renderer/RenderType;)V
 * index=1 = RenderType 参数（index=0 是 int layer）。
 *
 * 注意不要照抄 AR 对 Iris 的写法：compat.iris 的 AcceleratedBufferSourceMixin#unwrapIrisRenderType
 * 用的是 @ModifyArg(... AcceleratedBufferBuilder.<init> ..., index=5)，只改了 builder 的字段、map 键
 * 仍是 wrapper；这里改在 LayerKey 构造处，map 键与 builder.layerKey 一起规范化。
 *
 * AR 未安装（或本类目标缺失）时，本 mixin 与另一个 IF 目标在同一配置里，由 required:false 整体静默跳过；
 * 装了 AR 时也不改变任何非 IF 绘制路径的行为（instanceof 不命中即原样返回）。
 *
 * L5（task-32）接线：开关改为 ARFIfLeakState.fixEnabled()
 *   优先级 = 运行时覆盖（/arforce ifleak fix on|off，A/B 分相用） > -Darforcemobile.ifKeyFix（默认 true）。
 *   计数：每次调用 CALLS++；"键可规范化"（instanceof + original != null）时 CANON++；
 *   但只有 fixEnabled() 时才真的返回 original —— 这样 A/B 两相的计数口径一致，差别只体现在 arBuilders。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedBufferSource")
public class IfLayerKeyCanonicalizeMixin {

    @ModifyArg(method = "getBuffer",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/layers/LayerKey;<init>(ILnet/minecraft/client/renderer/RenderType;)V"),
            index = 1, remap = false)
    private RenderType arforcemobile$canonicalizeLayerKeyRenderType(RenderType renderType) {
        ARFIfLeakState.countCall();
        if (renderType instanceof ARFOriginalRenderType wrapped) {
            RenderType original = wrapped.arf$original();
            if (original != null) {
                ARFIfLeakState.countCanonicalized();
                if (ARFIfLeakState.fixEnabled()) {
                    return original;
                }
            }
        }
        return renderType;
    }
}
