package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：Sodium 侧的地形锚点。
 *
 * Sodium 用 @Overwrite 顶掉了原版 LevelRenderer#setupRender / #renderSectionLayer
 * （H-profiler §3.2 实证），所以装 Sodium 时只有这两个 public 方法会响；
 * 与 ProfilerLevelRendererMixin 的原版锚点共用同一阶段，靠 claim() 先到先得、禁止双计。
 * Sodium 是可选依赖：本 mixin 用 targets 字符串注册，Sodium 不在时由配置的
 * required:false 静默跳过（编译期用 libs/ 下的 dev-only stub 提供签名）。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public class ProfilerSodiumWorldRendererMixin {

    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;ZZ)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$terrainSetupHead(CallbackInfo ci) {
        ARFProbe.head(ARFProfile.H_TERRAIN_SETUP_SODIUM, ARFProfile.P_TERRAIN_SETUP);
    }

    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;ZZ)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$terrainSetupTail(CallbackInfo ci) {
        ARFProbe.tail(ARFProfile.H_TERRAIN_SETUP_SODIUM, ARFProfile.P_TERRAIN_SETUP);
    }

    @Inject(method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$layerHead(RenderType renderType, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
        if (!ARFProfile.enabled) {
            return;
        }
        ARFProfile.markHook(ARFProfile.H_TERRAIN_SODIUM);
        int phase = ARFProbe.terrainPhase(renderType);
        if (ARFProfile.claim(ARFProfile.H_TERRAIN_SODIUM, phase)) {
            ARFProfile.enter(phase);
        }
    }

    @Inject(method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$layerTail(RenderType renderType, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
        if (!ARFProfile.enabled) {
            return;
        }
        int phase = ARFProbe.terrainPhase(renderType);
        if (ARFProfile.claim(ARFProfile.H_TERRAIN_SODIUM, phase)) {
            ARFProfile.exit(phase);
        }
    }
}
