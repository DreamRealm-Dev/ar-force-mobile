package com.arforcemobile.mixin;

import com.arforcemobile.ARFDivisorState;
import com.arforcemobile.ARFMemCompare;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFSubmitTracker;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * UBO 内容去重（R6 → C10 顺带产出）。
 *
 * javap 实证：UniformBuffer.bind() 为 if(needsUpload){buffer.upload(clientBuffer);
 * needsUpload=false;} + glBindBufferRange。Uniforms.update() 每帧把
 * Frame/Player/Level/Fog/Options 五块全部 markDirty——R6 实测静止视角下
 * uploads=5×beginFrames，即 5 次/帧上传的内容逐字节重复（viewProj/雾/选项
 * 静态时根本没变）。本 mixin 在 bind HEAD 比对 clientBuffer 与上次快照，
 * 全等则压掉 needsUpload（跳过 upload，glBindBufferRange 照做）。
 *
 * fail-open 天然：比对异常或首帧无快照 → 照常上传；比对错杀 → 下帧内容差
 * 自会放行。@Unique 快照懒分配（K16），delete 时释放。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.uniform.UniformBuffer")
public abstract class UniformBufferDedupMixin {

    @Shadow(remap = false)
    @Final
    private MemoryBlock clientBuffer;

    @Shadow(remap = false)
    private boolean needsUpload;

    @Unique
    private MemoryBlock arforcemobile$snapshot;

    @Unique
    private boolean arforcemobile$hasSnapshot;

    @Inject(method = "bind()V", at = @At("HEAD"), remap = false)
    private void arforcemobile$dedupUpload(CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile() || !ARFDivisorState.uboEffectiveEnabled()) {
            return;
        }
        try {
            if (!needsUpload || clientBuffer == null) {
                return;
            }
            long size = clientBuffer.size();
            if (size <= 0) {
                return;
            }
            if (arforcemobile$snapshot == null || arforcemobile$snapshot.size() != size || arforcemobile$snapshot.isFreed()) {
                if (arforcemobile$snapshot != null && !arforcemobile$snapshot.isFreed()) {
                    arforcemobile$snapshot.free();
                }
                arforcemobile$snapshot = MemoryBlock.malloc(size);
                arforcemobile$hasSnapshot = false;
            }
            if (arforcemobile$hasSnapshot && ARFMemCompare.equal(clientBuffer, arforcemobile$snapshot)) {
                needsUpload = false;
                ARFSubmitTracker.onUboSkip();
                return;
            }
            clientBuffer.copyTo(arforcemobile$snapshot);
            arforcemobile$hasSnapshot = true;
        } catch (Throwable t) {
            arforcemobile$hasSnapshot = false;
        }
    }

    @Inject(method = "delete()V", at = @At("TAIL"), remap = false)
    private void arforcemobile$freeSnapshot(CallbackInfo ci) {
        if (arforcemobile$snapshot != null) {
            try {
                if (!arforcemobile$snapshot.isFreed()) {
                    arforcemobile$snapshot.free();
                }
            } catch (Throwable ignored) {
            }
            arforcemobile$snapshot = null;
        }
    }
}
