package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：hud。
 * hud 与 screen 可能嵌套（屏幕打开时 HUD 仍在画）⇒ ARFProfile 的 inclusive 去重负责不双计。
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public class ProfilerGuiMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$hudHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_HUD, ARFProfile.P_HUD);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$hudTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_HUD, ARFProfile.P_HUD);
    }
}
