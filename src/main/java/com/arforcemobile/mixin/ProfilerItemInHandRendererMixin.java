package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** W3 归因器钩子（1.3.26）：hand（第一人称手/物品动画，在 GameRenderer.renderLevel 内）。 */
@Mixin(targets = "net.minecraft.client.renderer.ItemInHandRenderer")
public class ProfilerItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$handHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_HAND, ARFProfile.P_HAND);
    }

    @Inject(method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$handTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_HAND, ARFProfile.P_HAND);
    }
}
