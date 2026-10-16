package com.arforcemobile.mixin;

import com.arforcemobile.ARFIfLeakState;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * L3/L5 泄漏观测仪（默认关闭，仅诊断用）：统计 ImmediatelyFast 包裹层的累计构造数。
 *
 * 开启方式：-Darforcemobile.leakWatch=true（直接读该属性，不经 ARSimArgs）。
 * 计数与 30 秒一行的 LEAK-WATCH 日志都搬到了 ARFIfLeakState（单一计数源，供
 * /arforce ifleak status 与 A/B 分相读取）；本类只保留"默认关"的热路径：
 * 一次 static final boolean 读取 + 一次 return，无分配、无日志、无行为改动。
 *
 * 与 IfWrappedRenderLayerMixin 各自 @Inject 同一个构造器 TAIL，互不依赖；
 * 两个 mixin 都不碰 equals/hashCode（AR 自己注入了它们）。
 */
@Mixin(targets = "net.raphimc.immediatelyfast.feature.batching.BatchingBuffers$WrappedRenderLayer")
public class IfWrapperLeakWatchMixin {

    /** 类初始化时读一次系统属性；默认 false ⇒ 热路径零分配、零日志。 */
    private static final boolean WATCH = ARFIfLeakState.watchEnabled();

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/RenderType;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
            at = @At("TAIL"), remap = false)
    private void arforcemobile$leakWatch(RenderType original, Runnable startAction, Runnable endAction,
                                         CallbackInfo ci) {
        if (!WATCH) {
            return;
        }
        ARFIfLeakState.onWrapperConstructed();
    }
}
