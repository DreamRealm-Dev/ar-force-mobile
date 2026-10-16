package com.arforcemobile;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L5：无输入驱动的 A/B/A/B 自动分相自测（task-32）。
 *
 * 用法：-Darforcemobile.ifleakAuto=&lt;秒&gt;（不传 = 本类完全不注册监听器，零开销）。
 * 世界加载完成后开始，每相 &lt;秒&gt; 秒，相序固定：
 *   A(fix=OFF) -&gt; B(fix=ON) -&gt; C(fix=OFF) -&gt; D(fix=ON)
 * 时间对称（A/B/A/B），用于在没人操作键盘鼠标时对比"修/不修"两条曲线。
 *
 * 每相：
 *   start: [ARForceMobile] IFLEAK PHASE &lt;name&gt; start &lt;statusLine&gt;
 *   end:   [ARForceMobile] IFLEAK PHASE &lt;name&gt; end &lt;statusLine&gt; dWrappers=&lt;…&gt; dArBuilders=&lt;…&gt; secs=&lt;…&gt;
 * 全部走完：一行 [ARForceMobile] IFLEAK DONE（之后 tick 立即返回，不再做任何事）。
 *
 * 实现细节：挂在客户端 tick（ClientTickEvent.Post，20/s）上；相位切换只调
 * ARFIfLeakState.setFixRuntime(...)，不碰任何配置/其它状态。
 */
public final class ARFIfLeakAutoRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final String PROPERTY = "arforcemobile.ifleakAuto";

    /** 相位秒数：<= 0 = 关闭。 */
    private static final int PHASE_SECONDS = parsePhaseSeconds();

    /** 相序：A(fix=OFF) -&gt; B(fix=ON) -&gt; C(fix=OFF) -&gt; D(fix=ON)。 */
    private static final String[] PHASE_NAMES = {"A(fix=OFF)", "B(fix=ON)", "C(fix=OFF)", "D(fix=ON)"};
    private static final boolean[] PHASE_FIX = {false, true, false, true};

    private static int phaseIndex = -1;
    private static long phaseStartNanos;
    private static long phaseStartWrappers;
    private static long phaseStartArBuilders;
    private static boolean worldReadyLogged;
    private static boolean done;

    private ARFIfLeakAutoRunner() {
    }

    /** mod 构造期调用；不传属性时连监听器都不注册（行为与 1.3.29-ifleak 完全一致）。 */
    public static void init() {
        if (PHASE_SECONDS <= 0) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ARFIfLeakAutoRunner::onClientTick);
        LOGGER.info("[ARForceMobile] IFLEAK auto runner armed: {}s per phase, phases A(fix=OFF) B(fix=ON) "
                + "C(fix=OFF) D(fix=ON) - waiting for world load", PHASE_SECONDS);
    }

    public static boolean armed() {
        return PHASE_SECONDS > 0;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        Minecraft minecraft;
        try {
            minecraft = Minecraft.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!worldReadyLogged) {
            worldReadyLogged = true;
            LOGGER.info("[ARForceMobile] IFLEAK auto: world ready, starting phases ({}s each)", PHASE_SECONDS);
        }

        long now = System.nanoTime();
        if (phaseIndex < 0) {
            startPhase(0, now);
            return;
        }
        if (now - phaseStartNanos < PHASE_SECONDS * 1_000_000_000L) {
            return;
        }
        endPhase(phaseIndex, now);
        int next = phaseIndex + 1;
        if (next >= PHASE_NAMES.length) {
            done = true;
            ARFIfLeakState.logStatus("DONE"); // 一行 [ARForceMobile] IFLEAK DONE fix=... arBuilders=...
            return;
        }
        startPhase(next, now);
    }

    private static void startPhase(int index, long now) {
        phaseIndex = index;
        phaseStartNanos = now;
        phaseStartWrappers = ARFIfLeakState.wrappers();
        phaseStartArBuilders = ARFIfLeakState.arBuilderTableSize();
        ARFIfLeakState.setFixRuntime(PHASE_FIX[index]);
        ARFIfLeakState.logStatus("PHASE " + PHASE_NAMES[index] + " start");
    }

    private static void endPhase(int index, long now) {
        long wrappers = ARFIfLeakState.wrappers();
        long arBuilders = ARFIfLeakState.arBuilderTableSize();
        String line = "PHASE " + PHASE_NAMES[index] + " end " + ARFIfLeakState.statusLine()
                + " dWrappers=" + (wrappers - phaseStartWrappers)
                + " dArBuilders=" + (arBuilders < 0L || phaseStartArBuilders < 0L
                        ? "n/a" : String.valueOf(arBuilders - phaseStartArBuilders))
                + " secs=" + String.format(java.util.Locale.ROOT, "%.1f", (now - phaseStartNanos) / 1e9);
        LOGGER.info("[ARForceMobile] IFLEAK {}", line);
    }

    private static int parsePhaseSeconds() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null) {
            return 0;
        }
        raw = raw.trim();
        if (raw.isEmpty() || "true".equalsIgnoreCase(raw)) {
            return 30;
        }
        try {
            int seconds = Integer.parseInt(raw);
            if (seconds <= 0) {
                return 0;
            }
            return Math.min(seconds, 3600);
        } catch (NumberFormatException e) {
            LOGGER.warn("[ARForceMobile] IFLEAK auto: cannot parse -D{}={} - using 30s per phase", PROPERTY, raw);
            return 30;
        }
    }
}
