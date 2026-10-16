package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：frame = GameRenderer#render(DeltaTracker,boolean)。
 *
 * 一帧里"提交给 GL 的 CPU 时间"的总口径（RenderFrameEvent.Pre 相邻差 = outside 含 flip/vsync/限帧）。
 * 默认关时 ARFProbe.head/tail 只做一次静态布尔读。
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public class ProfilerGameRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), remap = false)
    private void arforcemobile$frameHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_FRAME, ARFProfile.P_FRAME);
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"), remap = false)
    private void arforcemobile$frameTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_FRAME, ARFProfile.P_FRAME);
    }
}
