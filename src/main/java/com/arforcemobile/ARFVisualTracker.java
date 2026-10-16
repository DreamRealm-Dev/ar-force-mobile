package com.arforcemobile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContraptionVisual 视觉层计数器（S4 计数先行，纯只读诊断）。
 *
 * 为 S2（视锥/距离门）立项提供数据：定量测出 contraption 的
 * "场外占比"（beginFrame 里 isVisible 为 false 的比例）、
 * planTick/planFrame 调用频度、每台 actor 数。
 * 零行为改动：isVisible 只读调用、不取消、不放行任何原逻辑。
 *
 * 单独打一行，不并入 ARFTboTracker 的 8 字段诊断行（格式不破，§10）。
 */
public final class ARFVisualTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final long WINDOW_NANOS = 5_000_000_000L;

    // 诊断计数器（仅渲染线程访问；isMobile 首行闸门已在外面）
    private static long windowStartNanos;
    private static long beginFrameCalls;
    private static long planTickCalls;
    private static long visibleFrames;
    private static long invisibleFrames;
    private static long actorSetups;
    private static long actorCountMax;

    private ARFVisualTracker() {
    }

    public static void onBeginFrame() {
        beginFrameCalls++;
    }

    public static void onFrameVisible() {
        visibleFrames++;
    }

    public static void onFrameInvisible() {
        invisibleFrames++;
    }

    public static void onPlanTick() {
        planTickCalls++;
    }

    public static void onActorSetup(int currentActorCount) {
        actorSetups++;
        if (currentActorCount > actorCountMax) {
            actorCountMax = currentActorCount;
        }
    }

    /** 每 5 秒汇总打印一次（ContraptionVisual beginFrame 钩子里顺带调用）。 */
    public static void tickDiagnostics() {
        long now = System.nanoTime();
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
            return;
        }
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        double seconds = (now - windowStartNanos) / 1_000_000_000.0;
        long total = visibleFrames + invisibleFrames;
        // 场外占比：isVisible=false 的 beginFrame 占比，S2 立项依据（§14.3：>20% 立项，<5% 降级）
        long outOfViewPercent = total == 0L ? 0L : invisibleFrames * 100L / total;
        LOGGER.info("[ARForceMobile] Contraption visual counters (per {}s): beginFrames={} planTicks={} visible={} invisible={} outOfViewPct={} actorSetups={} actorCountMax={}",
                String.format("%.1f", seconds), beginFrameCalls, planTickCalls,
                visibleFrames, invisibleFrames, outOfViewPercent, actorSetups, actorCountMax);
        windowStartNanos = now;
        beginFrameCalls = 0;
        planTickCalls = 0;
        visibleFrames = 0;
        invisibleFrames = 0;
        actorSetups = 0;
        actorCountMax = 0;
    }
}
