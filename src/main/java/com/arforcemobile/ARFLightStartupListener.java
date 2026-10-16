package com.arforcemobile;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * I1 启动应用的**一次性**监听器（1.3.28 第 4 条）。
 *
 * 旧实现：ARForceMobile 无条件挂一个 `RenderFrameEvent.Pre` 的 lambda 监听——
 * 桌面在付、I1 关也在付（每帧一次分发，且永远无法注销）。
 * 新实现：注册的是**可注销的实例监听器**；第一次收到事件就
 * `NeoForge.EVENT_BUS.unregister(this)`，然后执行 {@link ARFLightState#applyAtStartup()}
 * （幂等；`flywheel_light_smoothness=off` 时直接返回）。⇒ **稳态零成本**。
 *
 * 为什么不在构造期直接读配置决定"要不要注册"：`ModConfigSpec` 的值在 config 加载前
 * 读取会抛（`Cannot get config value without assigned Config object present`），而构造期
 * 一定早于 config 加载 —— 所以用"注册 + 一次性注销"来保证零成本。
 */
public final class ARFLightStartupListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static volatile ARFLightStartupListener instance;

    private boolean done;

    private ARFLightStartupListener() {
    }

    /** 装配期调用一次：挂上一次性监听器（模态 = 实例方法 + @SubscribeEvent，可注销）。 */
    public static void register() {
        try {
            ARFLightStartupListener listener = new ARFLightStartupListener();
            instance = listener;
            NeoForge.EVENT_BUS.register(listener);
        } catch (Throwable t) {
            LOGGER.warn("[ARForceMobile] lightSmoothness startup listener registration failed: {}", t.toString());
        }
    }

    @SubscribeEvent
    public void onRenderFramePre(RenderFrameEvent.Pre event) {
        if (done) {
            return; // 注销失败时的兜底：最多多跑几次空判断
        }
        done = true;
        try {
            NeoForge.EVENT_BUS.unregister(this);
        } catch (Throwable ignored) {
            // 注销失败不影响正确性（done 已置位，稳态只剩一次布尔读）
        }
        ARFLightState.applyAtStartup();
    }

    /** 供诊断/自测查看是否已注销（未开启 I1 时也应变为 true）。 */
    public static boolean finished() {
        ARFLightStartupListener listener = instance;
        return listener == null || listener.done;
    }
}
