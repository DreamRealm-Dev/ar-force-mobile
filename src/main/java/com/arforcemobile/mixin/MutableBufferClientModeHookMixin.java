package com.arforcemobile.mixin;

import com.arforcemobile.ARClientBufferUploader;
import com.arforcemobile.ARClientBufferView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MutableBuffer 层的客户端模式配套钩子：
 * - unmap()：客户端模式下从未做过 GL 映射，对未映射缓冲调 glUnmapNamedBuffer 会产生
 *   GL_INVALID_OPERATION（扩容流程 beforeExpand 会调它），直接取消；
 * - delete()：释放客户端内存并移出上传注册表。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.backends.buffers.MutableBuffer")
public abstract class MutableBufferClientModeHookMixin {

    @Inject(method = "unmap()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void arforcemobile$skipUnmapInClientMode(CallbackInfo ci) {
        if ((Object) this instanceof ARClientBufferView v && v.arf$isClientMode()) {
            ci.cancel();
        }
    }

    @Inject(method = "delete()V", at = @At("HEAD"), remap = false)
    private void arforcemobile$freeClientMemory(CallbackInfo ci) {
        if ((Object) this instanceof ARClientBufferView v && v.arf$isClientMode()) {
            v.arf$releaseClient();
        }
    }
}
