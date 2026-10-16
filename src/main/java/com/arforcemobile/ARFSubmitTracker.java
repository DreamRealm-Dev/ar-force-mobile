package com.arforcemobile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S4 延伸（P6）：instancing 提交层计数器，纯只读诊断。
 *
 * R6 把上传层全线结案后（C10），instancing vs off 的差距只剩两个候选：
 * 顶点层 TBO 采样税（S5 的靶子）与每帧提交/JNI 固定税。本行量化后者：
 * 每帧 draw 次数、真 TBO 重绑次数（总 bind − 去重命中）、bindAll 次数。
 * S5 上线后同行还能对照 divisorDraws/tboTrueBinds 的此消彼长。
 *
 * 单独打一行，不并入既有诊断行（格式不破，§10）。
 */
public final class ARFSubmitTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final long WINDOW_NANOS = 5_000_000_000L;

    private static long windowStartNanos;
    private static long frames;
    private static long drawCalls;
    private static long instances;
    private static long divisorDraws;
    private static long tboTrueBinds;
    private static long bindAllCalls;
    private static long uboSkips;
    private static long s5Fallbacks;
    /** W4（1.3.26）：本窗口内累计提交顶点数（每条 draw 的 indexCount × instanceCount 之和）。 */
    private static long vertsPerFrame;
    /** W4（1.3.26）：本窗口内 oitDraws 非空的帧数。 */
    private static long oitFrames;
    /** W4（1.3.26）：这些帧的 oitDraws.size() 之和。 */
    private static long oitDraws;

    private ARFSubmitTracker() {
    }

    private static boolean diagnosticsOn() {
        if (!ARForceEnvironment.isMobile()) {
            return false;
        }
        try {
            return ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void onFrame() {
        if (!diagnosticsOn()) {
            return;
        }
        frames++;
    }

    public static void onDraw(int instanceCount, int indexCount) {
        if (!diagnosticsOn()) {
            return;
        }
        drawCalls++;
        instances += instanceCount;
        // W4（1.3.26）：本帧提交的顶点数（indexCount 每 draw 取自 mesh，instanceCount 取自 instancer）
        if (indexCount > 0 && instanceCount > 0) {
            vertsPerFrame += (long) indexCount * (long) instanceCount;
        }
    }

    /**
     * W4（1.3.26）：OIT 帧计数。**唯一采集点** = InstancedDrawManagerMixin 的 render TAIL
     * （读一次私有字段 oitDraws 的长度）：P6 行在这里累加，PROFILE 行由同一份数据驱动
     * （ARFProfile.oitFrame），不再二次采集（H-review §2.2 / A14 裁决）。
     */
    public static void onOitFrame(int oitDrawCount) {
        if (!diagnosticsOn()) {
            return;
        }
        if (oitDrawCount > 0) {
            oitFrames++;
            oitDraws += oitDrawCount;
        }
    }

    public static void onDivisorDraw() {
        if (!diagnosticsOn()) {
            return;
        }
        divisorDraws++;
    }

    /** TextureBufferDedupMixin 未命中（真 glTexBuffer 重绑）路径。 */
    public static void onTboTrueBind() {
        if (!diagnosticsOn()) {
            return;
        }
        tboTrueBinds++;
    }

    public static void onBindAll() {
        if (!diagnosticsOn()) {
            return;
        }
        bindAllCalls++;
    }

    public static void onUboSkip() {
        if (!diagnosticsOn()) {
            return;
        }
        uboSkips++;
    }

    /** 降级事件永久累计（不受诊断开关 gate——事件罕见，WARN 已另打）。 */
    public static void onS5Fallback() {
        s5Fallbacks++;
    }

    /** 每帧由 InstancedDrawManager.render TAIL 调用，5s 窗口打印。 */
    public static void tickDiagnostics() {
        if (!diagnosticsOn()) {
            return;
        }
        long now = System.nanoTime();
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
            return;
        }
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        double seconds = (now - windowStartNanos) / 1_000_000_000.0;
        // 1.3.26（W4）：格式不破——新字段只追加在行尾（既有字段与顺序不动）。
        LOGGER.info("[ARForceMobile] Instancing submit counters (per {}s): frames={} drawCalls={} instances={} divisorDraws={} tboTrueBinds={} bindAllCalls={} uboSkips={} s5Fallbacks={} vertsPerFrame={} oitFrames={} oitDraws={}",
                String.format("%.1f", seconds), frames, drawCalls, instances, divisorDraws,
                tboTrueBinds, bindAllCalls, uboSkips, s5Fallbacks, vertsPerFrame, oitFrames, oitDraws);
        windowStartNanos = now;
        frames = 0;
        drawCalls = 0;
        instances = 0;
        divisorDraws = 0;
        tboTrueBinds = 0;
        bindAllCalls = 0;
        uboSkips = 0;
        vertsPerFrame = 0;
        oitFrames = 0;
        oitDraws = 0;
    }
}
