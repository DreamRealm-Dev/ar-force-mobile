package com.arforcemobile.mixin;

import com.arforcemobile.ARFOriginalRenderType;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * L3 泄漏修复（L1 §2 R1 第 1 步）：捕获 ImmediatelyFast 包裹层的原始 RenderType。
 *
 * IF 的构造器签名（javap，IF 1.6.13）：
 *   WrappedRenderLayer(RenderType original, Runnable startAction, Runnable endAction)
 * 构造器内部还会用 original 的 name/format/mode/bufferSize/affectsCrumbling/sortOnUpload
 * 去 super(...)，并新建两个捕获 lambda。
 *
 * 本 mixin 只做「只读捕获 + 暴露接口」，绝不触碰 equals/hashCode/canEqual：
 * AR 自带的 compat.immediatelyfast.mixins.WrappedRenderLayerMixin 已经注入了这三个方法，
 * 重复注入会互相覆盖。IF 是可选依赖，用 targets 字符串 + 配置 required:false 静默跳过。
 */
@Mixin(targets = "net.raphimc.immediatelyfast.feature.batching.BatchingBuffers$WrappedRenderLayer")
public class IfWrappedRenderLayerMixin implements ARFOriginalRenderType {

    @Unique
    private RenderType arf$original;

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/RenderType;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
            at = @At("TAIL"), remap = false)
    private void arforcemobile$captureOriginal(RenderType original, Runnable startAction, Runnable endAction,
                                               CallbackInfo ci) {
        this.arf$original = original;
    }

    @Override
    public RenderType arf$original() {
        return this.arf$original;
    }
}
