package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：screen。
 * 注意：注入的是 Screen#render 本身，子类若 override 且不调 super.render 则不计入（已知边界，
 * 写进报告；Overlay 等同理由部分覆盖）。
 */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen")
public class ProfilerScreenMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), remap = false)
    private void arforcemobile$screenHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_SCREEN, ARFProfile.P_SCREEN);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"), remap = false)
    private void arforcemobile$screenTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_SCREEN, ARFProfile.P_SCREEN);
    }
}
