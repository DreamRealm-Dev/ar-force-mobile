package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import com.arforcemobile.ARFSubmitTracker;
import com.arforcemobile.ARMapProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel TextureBuffer 的 glTexBuffer 重绑去重。
 *
 * bind(I) 每个 draw call 都执行 glBindTexture(GL_TEXTURE_BUFFER)+glTexBuffer。
 * TBO 的纹理附件跟随缓冲对象本身：内容未变时重复 attach 是纯冗余——
 * 在 MG 的 TBO 模拟路径（ES<=3.1 驱动）上每次调用还会全量重拷整个实例缓冲。
 * 以 GlBuffer 的内容版本号（ARFTboTracker）判断：数据未变直接跳过。
 * 版本号在上传/孤儿化/删除时都会正确失效，模拟与原生路径下均安全。
 *
 * 绑定条件（1.3.11 修订）：{@code flywheel_tbo_native_dedup=true}（默认）时全驱动
 * 按内容版本号去重——即使原生 glTexBuffer 直通，去重仍省 JNI 与附件校验，且
 * MG 2.0 的 buffer_storage/DSA 行为已变，旧“持久映射成功=直通零收益”假设需重验。
 * 置 false 则回到旧自动行为（仅持久映射探测失败时启用）。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.gl.TextureBuffer")
public abstract class TextureBufferDedupMixin {

    @Unique
    private int arf$lastBuffer = -1;

    @Unique
    private long arf$lastVersion;

    @Unique
    private boolean arf$hasLast;

    @Inject(method = "bind(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void arforcemobile$dedupBind(int buffer, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.FLYWHEEL_TBO_DEDUP.get()) {
            return;
        }
        if (!ARForceConfig.FLYWHEEL_TBO_NATIVE_DEDUP.get() && ARMapProbe.persistentMapWorks()) {
            // 旧自动行为：原生持久映射可用 -> 现代驱动 -> 跳过去重
            return;
        }
        long version = ARFTboTracker.version(buffer);
        if (arf$hasLast && arf$lastBuffer == buffer && arf$lastVersion == version) {
            ARFTboTracker.onTboDedup();
            ci.cancel();
            return;
        }
        // 未命中＝真 glTexBuffer 重绑（P6 提交层口径：tboTrueBinds）
        ARFSubmitTracker.onTboTrueBind();
        arf$lastBuffer = buffer;
        arf$lastVersion = version;
        arf$hasLast = true;
    }
}
