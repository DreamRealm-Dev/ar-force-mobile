package com.arforcemobile.mixin;

import com.arforcemobile.ARClientBufferUploader;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.LayerDrawType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AcceleratedBufferSource 层的上传时机钩子（客户端内存回退专用，非客户端模式零开销）。
 *
 * 时机依据 AR 1.0.14 的调用结构：
 * - prepareBuffers() HEAD：meshUpload/transform/culling compute 在 prepareBuffers 内部
 *   以 SSBO 读取 CPU 写入的数据，上传必须发生在 compute 派发之前；
 * - drawBuffers() HEAD：后续帧内晚写入（GUI/文本层等）在其消费前的兜底上传点
 *   （增量游标保证重复调用零成本）。
 *
 * 顶点属性 VAO 只在 layout 变化时绑定一次，不能靠 bind 时机上传，必须在源头统一 flush。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedBufferSource")
public class AcceleratedBufferSourceFlushMixin {

    @Inject(method = "prepareBuffers", at = @At("HEAD"), remap = false)
    private void arforcemobile$flushBeforeCompute(CallbackInfo ci) {
        ARClientBufferUploader.flushAll();
    }

    @Inject(method = "drawBuffers", at = @At("HEAD"), remap = false)
    private void arforcemobile$flushBeforeDraw(LayerDrawType layerDrawType, CallbackInfo ci) {
        ARClientBufferUploader.flushAll();
    }
}
