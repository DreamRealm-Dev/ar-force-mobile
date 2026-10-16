package com.arforcemobile;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W3 帧阶段归因器的装配层（1.3.26）。
 *
 * 纪律（H-profiler §9）：
 * - {@code -Darforcemobile.profile} 默认 false ⇒ {@link #init()} 什么都不注册、不打任何 PROFILE 行；
 * - 开启时才 {@code ARFProfile.configure()} + 挂 {@link RenderFrameEvent.Pre}（复用 ARBench 的注册方式）
 *   + 退出世界 / 关游戏两个 summarize；
 * - 全程 try/catch 静默降级，绝不影响渲染。
 */
public final class ARFProfilerBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean initialized;

    private ARFProfilerBootstrap() {
    }

    /** silent_required 的告警只打一次（第一个出现该字段的窗口，即首个窗口）。 */
    private static boolean hookWarningEmitted;

    /** 1.3.29（task-37 I-4）：钩子"漏登记"告警只打一次（既不在 required 也不在 optional）。 */
    private static boolean uncoveredWarningEmitted;

    /**
     * PROFILE 输出出口（1.3.28 第 1 条）：首个窗口结束时若 `silent_required` 非空，
     * 用 WARN 明确说出"插桩未接上、读数不可信"——防止把「terrain_* 全 0.00」误读成
     * 「地形不花钱」。其余行走 INFO。
     */
    private static void onProfileLine(String line) {
        try {
            if (!hookWarningEmitted && line != null) {
                int start = line.indexOf("silent_required=[");
                if (start >= 0) {
                    int end = line.indexOf(']', start);
                    String missing = end > start ? line.substring(start + "silent_required=[".length(), end) : "";
                    if (!missing.isEmpty()) {
                        hookWarningEmitted = true;
                        LOGGER.warn("[ARForceMobile] Profile hooks missing: {} — instrumentation not connected, "
                                + "PROFILE numbers are NOT trustworthy (fix the hooks before drawing conclusions).",
                                missing);
                    }
                }
            }
            // 1.3.29（I-4）：某个钩子既不在 required 也不在 optional 名单里 -> 它的缺席两栏都看不见
            if (!uncoveredWarningEmitted) {
                String uncovered = ARFProfile.uncoveredHooks();
                if (!uncovered.equals("[]")) {
                    uncoveredWarningEmitted = true;
                    LOGGER.warn("[ARForceMobile] Profile hooks not listed in REQUIRED_GROUPS/OPTIONAL_HOOKS: {}"
                            + " — a silent hook would be invisible; please register it.", uncovered);
                }
            }
        } catch (Throwable ignored) {
            // 诊断输出永不抛
        }
        LOGGER.info("{}", line);
    }

    public static void init() {
        try {
            if (initialized || !Boolean.TRUE.equals(ARSimArgs.profile())) {
                return;
            }
            initialized = true;
            int interval = ARSimArgs.profileIntervalSeconds();
            int stride = ARSimArgs.profileStride();
            // 1.3.29（task-37 I-5）：预热期（入世界后前 N 秒的窗口打 warmup=1，且不进 SESSION 的 steady 组）
            int warmup = Integer.getInteger("arforcemobile.profileWarmup", 20);
            ARFProfile.enabled = true;
            ARFProfile.setWarmupSeconds(warmup);
            ARFProfile.configure(interval, stride, ARFProfilerBootstrap::onProfileLine);
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
                try {
                    Minecraft minecraft = Minecraft.getInstance();
                    ARFProfile.frameTick(minecraft != null && minecraft.level != null);
                } catch (Throwable ignored) {
                    // 静默降级
                }
            });
            NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ARFProfile.summarize("logout"));
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> ARFProfile.summarize("shutdown"), "arforcemobile-profile"));
            } catch (Throwable ignored) {
                // 关游戏总结拿不到就算了
            }
            LOGGER.info("[ARForceMobile] PROFILER enabled: interval={}s stride={} warmup={}s (W3 frame phase attribution; "
                    + "one PROFILE window every {}s)", interval, stride, warmup, interval);
        } catch (Throwable ignored) {
            // 静默降级
        }
    }
}
