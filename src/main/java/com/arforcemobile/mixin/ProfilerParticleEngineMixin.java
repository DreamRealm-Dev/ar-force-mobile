package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：particles。
 * ParticleEngine#render 有两个重载 ⇒ 必须写全 5 参描述符（带 Frustum/Predicate 的那个）。
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleEngine")
public class ProfilerParticleEngineMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$particlesHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_PARTICLES, ARFProfile.P_PARTICLES);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$particlesTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_PARTICLES, ARFProfile.P_PARTICLES);
    }
}
