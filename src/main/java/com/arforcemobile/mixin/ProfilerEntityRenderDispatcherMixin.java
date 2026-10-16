package com.arforcemobile.mixin;

import com.arforcemobile.ARFProbe;
import com.arforcemobile.ARFProfile;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * W3 归因器钩子（1.3.26）：entities / entities_contraption。
 *
 * 挂在 EntityRenderDispatcher#render（而不是 LevelRenderer#renderEntity）：后者会被
 * Flywheel 的 skipVanillaRender 取消（H-profiler §3.3），挂它会把 entities 系统性低估。
 * Create 的 contraption 实体单独记一档（软依赖判定见 ARFProbe.isContraption）。
 * 实体渲染可能嵌套（乘客），用 ARFProbe 里的 LIFO 栈保证 enter/exit 成对。
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.EntityRenderDispatcher")
public class ProfilerEntityRenderDispatcherMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$entitiesHead(Entity entity, double x, double y, double z, float rotationYaw, float partialTick,
                                            com.mojang.blaze3d.vertex.PoseStack poseStack,
                                            net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight,
                                            CallbackInfo ci) {
        ARFProbe.entityHead(ARFProbe.isContraption(entity));
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), remap = false)
    private void arforcemobile$entitiesTail(Entity entity, double x, double y, double z, float rotationYaw, float partialTick,
                                            com.mojang.blaze3d.vertex.PoseStack poseStack,
                                            net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight,
                                            CallbackInfo ci) {
        ARFProbe.entityTail();
    }
}
