package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** W3 归因器钩子（1.3.26）：postfx（后处理：模糊/反胃/实体发光）。 */
@Mixin(targets = "net.minecraft.client.renderer.PostChain")
public class ProfilerPostChainMixin {

    @Inject(method = "process(F)V", at = @At("HEAD"), remap = false)
    private void arforcemobile$postfxHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_POSTFX, ARFProfile.P_POSTFX);
    }

    @Inject(method = "process(F)V", at = @At("RETURN"), remap = false)
    private void arforcemobile$postfxTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_POSTFX, ARFProfile.P_POSTFX);
    }
}
