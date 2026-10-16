package com.arforcemobile.mixin;

import com.arforcemobile.ARFGlHandleView;
import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GlBuffer 内容版本号登记：
 * - upload/uploadSpan（数据变化）时按 handle 递增版本号，供 TextureBuffer 重绑去重判断；
 * - deleteInternal 时清理登记，防止驱动回收复用 handle 后误判；
 * - 顺带累计诊断计数（上传次数/字节）。
 * 桌面环境全部 early-return，零开销。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.gl.buffer.GlBuffer")
public abstract class GlBufferTrackerMixin {

    @Inject(method = "upload(JJ)V", at = @At("TAIL"), remap = false)
    private void arforcemobile$onUpload(long ptr, long size, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFTboTracker.bump(((ARFGlHandleView) (Object) this).arf$handle(), size);
        if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
            ARFTboTracker.tickDiagnostics();
        }
    }

    @Inject(method = "uploadSpan(JJJ)V", at = @At("TAIL"), remap = false)
    private void arforcemobile$onUploadSpan(long offset, long ptr, long size, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFTboTracker.bump(((ARFGlHandleView) (Object) this).arf$handle(), size);
        if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
            ARFTboTracker.tickDiagnostics();
        }
    }

    @Inject(method = "deleteInternal(I)V", at = @At("TAIL"), remap = false)
    private void arforcemobile$onDelete(int handle, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFTboTracker.invalidate(handle);
    }
}
