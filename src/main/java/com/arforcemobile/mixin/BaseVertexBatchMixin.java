package com.arforcemobile.mixin;

import com.arforcemobile.ARBaseVertexBatch;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.pools.IDrawContextPool;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.functions.ILayerFunction;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.storage.ILayerContexts;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;

/**
 * BaseVertex 合批入口(目标 AcceleratedBufferSource.prepareBuffers/drawBuffers/clearBuffers):
 *  - drawBuffers HEAD: 一次性诊断 + 清待提交组;
 *  - ILayerContexts.iterator: 返回按 RenderType 归拢的视图(不透明层);
 *  - RenderType.setupRenderState: RenderType 变化时先提交上一组(shader 仍绑定);
 *  - IDrawContext.drawElements: 同 RenderType 连续成员并入合批组,否则走原单条;
 *  - ShaderInstance.clear / RenderType.clearRenderState: 组内成员跳过,保持 shader 存活到 flush;
 *  - runBefore/runAfter/bindDrawBuffers/unbindVertexArray: 边界处提交残留组;
 *  - clearBuffers: 清空帧状态。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedBufferSource")
public class BaseVertexBatchMixin {

    @Inject(method = "drawBuffers", at = @At("HEAD"))
    private void arforcemobile$resetPending(CallbackInfo ci) {
        ARBaseVertexBatch.logDiagnosticsOnce();
        ARBaseVertexBatch.resetPending();
    }

    @Inject(method = "clearBuffers", at = @At("HEAD"))
    private void arforcemobile$resetFrame(CallbackInfo ci) {
        ARBaseVertexBatch.resetFrame();
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/layers/storage/ILayerContexts;iterator()Ljava/util/Iterator;"))
    private Iterator<IDrawContextPool.IDrawContext> arforcemobile$orderedIterator(ILayerContexts contexts) {
        List<IDrawContextPool.IDrawContext> view = ARBaseVertexBatch.orderedView(contexts);
        if (view != null) {
            return view.iterator();
        }
        return contexts.iterator();
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;setupRenderState()V"))
    private void arforcemobile$setupRenderState(RenderType renderType) {
        ARBaseVertexBatch.onSetupRenderState(renderType);
        renderType.setupRenderState();
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/draw/pools/IDrawContextPool$IDrawContext;drawElements(Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;)V"))
    private void arforcemobile$drawElements(IDrawContextPool.IDrawContext ctx, VertexFormat.Mode mode) {
        if (!ARBaseVertexBatch.onDrawElements(ctx, mode)) {
            ctx.drawElements(mode);
        }
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ShaderInstance;clear()V"))
    private void arforcemobile$clear(ShaderInstance shader) {
        if (!ARBaseVertexBatch.onClear(shader)) {
            shader.clear();
        }
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;clearRenderState()V"))
    private void arforcemobile$clearRenderState(RenderType renderType) {
        if (!ARBaseVertexBatch.onClearRenderState(renderType)) {
            renderType.clearRenderState();
        }
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/layers/functions/ILayerFunction;runBefore()V"))
    private void arforcemobile$runBefore(ILayerFunction function) {
        ARBaseVertexBatch.flushGroup();
        function.runBefore();
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/layers/functions/ILayerFunction;runAfter()V"))
    private void arforcemobile$runAfter(ILayerFunction function) {
        ARBaseVertexBatch.flushGroup();
        function.runAfter();
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/AcceleratedRingBuffers$Buffers;bindDrawBuffers()V"))
    private void arforcemobile$bindDrawBuffers(AcceleratedRingBuffers.Buffers buffers) {
        ARBaseVertexBatch.flushGroup();
        buffers.bindDrawBuffers();
    }

    @Redirect(method = "drawBuffers",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/AcceleratedRingBuffers$Buffers;unbindVertexArray()V"))
    private void arforcemobile$unbindVertexArray(AcceleratedRingBuffers.Buffers buffers) {
        ARBaseVertexBatch.flushGroup();
        buffers.unbindVertexArray();
    }
}
