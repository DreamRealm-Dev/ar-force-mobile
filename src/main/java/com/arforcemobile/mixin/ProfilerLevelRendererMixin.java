package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：原版 LevelRenderer 侧的世界/地形/天空/云/天气/调试。
 *
 * - world / terrain_setup / sky / clouds / weather / terrain_* / debug 七个阶段；
 * - terrain_setup 与 terrain_* 是"双候选"阶段：原版这套锚点在装 Sodium 时会被
 *   @Overwrite 顶掉（Sodium 侧见 ProfilerSodiumWorldRendererMixin），claim() 先到先得；
 * - terrain_* 按 RenderType 引用相等归类（solid/cutoutMipped/cutout/translucent/other）。
 * 默认关时每个钩子只花一次静态布尔读。
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public class ProfilerLevelRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$worldHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_WORLD, ARFProfile.P_WORLD);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$worldTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_WORLD, ARFProfile.P_WORLD);
    }

    @Inject(method = "setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$terrainSetupHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_TERRAIN_SETUP_VANILLA, ARFProfile.P_TERRAIN_SETUP);
    }

    @Inject(method = "setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$terrainSetupTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_TERRAIN_SETUP_VANILLA, ARFProfile.P_TERRAIN_SETUP);
    }

    @Inject(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$skyHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_SKY, ARFProfile.P_SKY);
    }

    @Inject(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$skyTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_SKY, ARFProfile.P_SKY);
    }

    @Inject(method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$cloudsHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_CLOUDS, ARFProfile.P_CLOUDS);
    }

    @Inject(method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$cloudsTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_CLOUDS, ARFProfile.P_CLOUDS);
    }

    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$weatherHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_WEATHER, ARFProfile.P_WEATHER);
    }

    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$weatherTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_WEATHER, ARFProfile.P_WEATHER);
    }

    @Inject(method = "renderWorldBorder(Lnet/minecraft/client/Camera;)V", at = @At("HEAD"), remap = false)
    private void arforcemobile$borderHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_WEATHER, ARFProfile.P_WEATHER);
    }

    @Inject(method = "renderWorldBorder(Lnet/minecraft/client/Camera;)V", at = @At("RETURN"), remap = false)
    private void arforcemobile$borderTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_WEATHER, ARFProfile.P_WEATHER);
    }

    @Inject(method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$layerHead(RenderType renderType, double x, double y, double z, Matrix4f frustum, Matrix4f projection, CallbackInfo ci) {
        if (!ARFProfile.enabled) {
            return;
        }
        ARFProfile.markHook(ARFProfile.H_TERRAIN_VANILLA);
        int phase = ARFProbe.terrainPhase(renderType);
        if (ARFProfile.claim(ARFProfile.H_TERRAIN_VANILLA, phase)) {
            ARFProfile.enter(phase);
        }
    }

    @Inject(method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$layerTail(RenderType renderType, double x, double y, double z, Matrix4f frustum, Matrix4f projection, CallbackInfo ci) {
        if (!ARFProfile.enabled) {
            return;
        }
        int phase = ARFProbe.terrainPhase(renderType);
        if (ARFProfile.claim(ARFProfile.H_TERRAIN_VANILLA, phase)) {
            ARFProfile.exit(phase);
        }
    }

    @Inject(method = "renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$debugHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_DEBUG, ARFProfile.P_DEBUG);
    }

    @Inject(method = "renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$debugTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_DEBUG, ARFProfile.P_DEBUG);
    }
}
