package com.arforcemobile.mixin;

import com.arforcemobile.ARFDivisorState;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFSubmitTracker;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer;
import dev.engine_room.flywheel.backend.gl.TextureBuffer;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S5 绑定侧：InstancedDraw.render/renderOne 里原 instancer.bind(tbo)
 * （每 draw 把 instancer vbo 重绑进全局 TBO 的 choke point）在 S5 激活时改为
 * divisor attribute 绑定（ARFDivisorState.bindInstanceBuffer）。
 *
 * 时序保证：submitDraws 前有 vao.bindForDraw（InstancedDrawManager.render 内，
 * javap 实证），redirect 执行时 VAO 处于 bound 状态，bindVertexBuffer/
 * bindAttributes/setBindingDivisor 的直接状态修改合法。
 * mesh 侧 binding 0 由 MeshPool.bind 设置，本侧只用 binding 1，互不干扰。
 *
 * fail-open：异常/缺 VAO → 全局 deactivate 并回原 TBO bind（此时 shader 若已
 * patched 则需重切 backend 恢复——WARN 已注明）。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw")
public abstract class InstancedDrawDivisorMixin {

    @Shadow(remap = false)
    @Final
    private InstancedInstancer instancer;

    /** W4（1.3.26）：本 draw 的 mesh（提供 indexCount，用于 vertsPerFrame）。 */
    @Shadow(remap = false)
    @Final
    private MeshPool.PooledMesh mesh;

    @Inject(method = "render(Ldev/engine_room/flywheel/backend/gl/TextureBuffer;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$onDraw(TextureBuffer instanceTexture, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFSubmitTracker.onDraw(instancer.instanceCount(), mesh == null ? 0 : mesh.indexCount());
    }

    @Inject(method = "renderOne(Ldev/engine_room/flywheel/backend/gl/TextureBuffer;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$onDrawOne(TextureBuffer instanceTexture, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFSubmitTracker.onDraw(1, mesh == null ? 0 : mesh.indexCount());
    }

    @Redirect(method = {"render(Ldev/engine_room/flywheel/backend/gl/TextureBuffer;)V",
            "renderOne(Ldev/engine_room/flywheel/backend/gl/TextureBuffer;)V"},
            at = @At(value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/backend/engine/instancing/InstancedInstancer;bind(Ldev/engine_room/flywheel/backend/gl/TextureBuffer;)V",
                    remap = false),
            remap = false)
    private static void arforcemobile$bindInstanceBuffer(InstancedInstancer instancer, TextureBuffer instanceTexture) {
        // patchApplied 联动闸：防"patch mixin 静默失效但本 mixin 活着"的 TBO shader＋divisor 绑定塌缩组合
        if (!ARForceEnvironment.isMobile() || !ARFDivisorState.effectiveEnabled() || !ARFDivisorState.isPatchApplied()) {
            instancer.bind(instanceTexture);
            return;
        }
        try {
            GlBuffer vbo = ((InstancedInstancerAccessor) (Object) instancer).arforcemobile$vbo();
            if (vbo == null) {
                return;
            }
            int stride = ((InstancedInstancerAccessor) (Object) instancer).arforcemobile$instanceStride();
            if (stride <= 0 || (stride & 15) != 0) {
                // stride 非 16 对齐会让 relativeOffset i*16 与 fetch 序号错位——
                // 概率≈0（Flywheel 布局按 16B 粒度打包），降级待重编译回退。
                ARFDivisorState.deactivate("instance stride " + stride + " is not 16-byte aligned");
                instancer.bind(instanceTexture);
                return;
            }
            GlVertexArray vao = ARFDivisorState.currentVao();
            if (vao == null) {
                ARFDivisorState.deactivate("no GlVertexArray captured");
                instancer.bind(instanceTexture);
                return;
            }
            ARFDivisorState.bindInstanceBuffer(vao, vbo.handle(), stride);
            ARFSubmitTracker.onDivisorDraw();
        } catch (Throwable t) {
            ARFDivisorState.deactivate("bind error: " + t);
            instancer.bind(instanceTexture);
        }
    }
}
