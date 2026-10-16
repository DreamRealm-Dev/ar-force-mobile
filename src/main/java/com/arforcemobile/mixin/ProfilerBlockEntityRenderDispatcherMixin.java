package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** W3 归因器钩子（1.3.26）：blockentities。Sodium 也走同一个 dispatcher（H-profiler §3.2-3）。 */
@Mixin(targets = "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher")
public class ProfilerBlockEntityRenderDispatcherMixin {

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$blockEntitiesHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_BLOCKENTITIES, ARFProfile.P_BLOCKENTITIES);
    }

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$blockEntitiesTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_BLOCKENTITIES, ARFProfile.P_BLOCKENTITIES);
    }
}
