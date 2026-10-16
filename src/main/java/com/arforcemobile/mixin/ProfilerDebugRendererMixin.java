package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：debug（F3 线框/命中盒等）。
 * 另一半（LevelRenderer#renderDebug）在 ProfilerLevelRendererMixin 里，共用同一阶段。
 */
@Mixin(targets = "net.minecraft.client.renderer.debug.DebugRenderer")
public class ProfilerDebugRendererMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$debugHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_DEBUG, ARFProfile.P_DEBUG);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$debugTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_DEBUG, ARFProfile.P_DEBUG);
    }
}
