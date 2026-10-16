package com.arforcemobile.mixin;

import com.arforcemobile.ARClientBufferUploader;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARMapMode;
import com.arforcemobile.ARMapProbe;
import org.lwjgl.opengl.GL46;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 客户端内存回退模式下替换 AR 不可变缓冲的存储创建（手机环境专用）。
 *
 * 1.2.8：仅补 GL_DYNAMIC_STORAGE_BIT，保留不可变存储，上传走 glNamedBufferSubData。
 * 实测（8Gen1 MCBench）该路径比 vanilla 还慢——移动驱动对"不可变存储上的 subData"
 * 没有 orphan 语义，只能原地改或整体影子拷贝，甚至停顿。
 *
 * 1.3.0：orphan 上传模式（默认，-Darforcemobile.clientUpload=orphan）下把
 * glNamedBufferStorage 换成 nglNamedBufferData 创建【可变】存储（vanilla 同款），
 * 之后每帧 glNamedBufferData 整段重声明 = 驱动换新存储 + 单次拷贝、零停顿，
 * 重声明对可变存储永远合法。subdata 模式维持 1.2.8 行为。
 *
 * 只在"确定不走原生持久映射"时替换存储：GL 规定 PERSISTENT 映射只允许
 * glBufferStorage 分配的存储——探测通过且未强制 client 的原生 tier 必须原样
 * 保留 AR 的 glNamedBufferStorage，否则原生路径会被降级。
 * 桌面环境零改动。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.backends.buffers.ImmutableBuffer")
public abstract class ImmutableBufferStorageFlagsMixin {

    @Redirect(method = "<init>(JI)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL46;glNamedBufferStorage(IJI)V"),
            remap = false)
    private void arforcemobile$replaceStorageCreation(int buffer, long size, int flags) {
        boolean nonNativeTier;
        try {
            // 只要"确定不走原生持久映射"就换成可变存储：tier-1 的普通映射对可变存储
            // 完全合法；client tier 的 orphan 重声明则必须可变。原生 tier（探测通过且
            // 未强制 client）保持 AR 原样——PERSISTENT 映射只允许 glBufferStorage 存储。
            nonNativeTier = ARForceEnvironment.isMobile()
                    && !ARMapMode.isNativeOnly()
                    && (ARMapMode.isForceClient() || !ARMapProbe.persistentMapWorks());
        } catch (Throwable t) {
            nonNativeTier = false;
        }
        if (nonNativeTier) {
            if (ARClientBufferUploader.ORPHAN_UPLOAD) {
                // 可变存储 + STREAM_DRAW：每帧整段重声明的 orphan 模式专用。
                GL46.nglNamedBufferData(buffer, size, 0L, GL46.GL_STREAM_DRAW);
                return;
            }
            flags |= GL46.GL_DYNAMIC_STORAGE_BIT;
        }
        GL46.glNamedBufferStorage(buffer, size, flags);
    }
}
