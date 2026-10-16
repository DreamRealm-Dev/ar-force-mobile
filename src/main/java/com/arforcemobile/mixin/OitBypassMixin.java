package com.arforcemobile.mixin;

import com.arforcemobile.ARFOitState;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * W2 OIT 旁路（1.3.27，默认关）：在 `InstancedDraw#material()` 的 RETURN 把
 * ORDER_INDEPENDENT 材质替换成 TRANSLUCENT + writeMask=COLOR 的变体。
 *
 * 为什么必须在 material() 这一层：该方法的 4 个调用点（render 分类 / submitDraws /
 * submitOitDraws / renderCrumbling）全部在 InstancedDrawManager 内，改这里以后
 * 分类、GL 状态（MaterialRenderState.setup -> 会真的设 blend）、程序选择（OitMode.OFF）、
 * material uniform 都一致地看到"TRANSLUCENT 材质"。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw")
public class OitBypassMixin {

    @Inject(method = "material", at = @At("RETURN"), cancellable = true, remap = false)
    private void arforcemobile$oitBypass(CallbackInfoReturnable<Material> cir) {
        try {
            if (!ARFOitState.bypassActive()) {
                return;
            }
            Material original = cir.getReturnValue();
            if (original == null || original.transparency() != Transparency.ORDER_INDEPENDENT) {
                return;
            }
            Material variant = ARFOitState.nonOitVariant(original);
            if (variant != null) {
                ARFOitState.countBypassed();
                cir.setReturnValue(variant);
            }
        } catch (Throwable t) {
            // fail-open：绝不让开关把渲染搞挂
            ARFOitState.disableThisSession(t);
        }
    }
}
