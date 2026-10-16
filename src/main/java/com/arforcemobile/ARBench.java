package com.arforcemobile;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帧时基准（1.3.23）：{@code -Darforcemobile.bench=true} 时按窗口打印 fps 统计，
 * 让 610 上的 A/B 不再依赖人肉看 F3。
 *
 * <p>设计纪律：
 * <ul>
 *   <li><b>默认关</b>：{@link #enabled()} 为 false 时 {@link #init()} 根本不会被调用
 *       （见 ARForceMobile），不注册任何监听、不装 shutdown hook、不打任何日志，
 *       行为与 1.3.22 完全一致；</li>
 *   <li><b>采样点 = {@code RenderFrameEvent.Pre}</b>：它由 Minecraft.runTick 在
 *       {@code gameRenderer.render(...)} 之前触发（NeoForge 补丁行：Minecraft.java:1199），
 *       即「上一帧的 blit → updateDisplay(flipFrame) → limitDisplayFPS → 本帧开头」之后。
 *       因此相邻两次 Pre 的时间差 = 一个完整帧周期（含 flipFrame 的 vsync 阻塞与
 *       FPS 限帧睡眠）。<b>不用 Post</b>：Post 在 render 之后、blit/updateDisplay/
 *       limitDisplayFPS 之前触发（Minecraft.java:1203），用它算会把帧后半段算丢，
 *       开了限帧时会测出高于实际呈现的 fps。</li>
 *   <li><b>只在世界内采样</b>：{@code Minecraft.getInstance().level == null}（主菜单/加载中）
 *       时不采样并清掉上一帧时间戳，避免把菜单的高帧率混进 A/B 数字；</li>
 *   <li><b>热路径零分配、零排序</b>：每帧只做两次 nanoTime + 一次数组写入 + 几个计数；
 *       排序与百分位只在窗口边界（默认 10 秒一次）做，排的是同一块预分配数组；</li>
 *   <li><b>静默降级</b>：所有入口 try/catch(Throwable)，任何异常都不许冒泡进渲染路径；</li>
 *   <li>超过 {@link #MAX_SAMPLE_NANOS}（10 秒）的“帧”按暂停/空档丢弃并计入 gaps，
 *       不污染均值与 1% low。</li>
 * </ul>
 *
 * <p>统计口径（每个窗口与会话总结相同）：
 * <pre>
 *   mean   = 窗口内全部帧的算术平均帧时（ms）与其倒数 fps
 *   median = 中位帧时（升序数组 nearest-rank，q=0.50）
 *   p95    = 第 95 百分位帧时（q=0.95）——即最慢 5% 帧的门槛
 *   1% low = 最慢 1% 帧的帧时均值（\u2265 1 帧）与其倒数 fps
 * </pre>
 * 1% low 是 A/B 最该看的数：它反映卡顿，而不是平均值。
 *
 * <p>会话总结（退出世界 / 关游戏）用预分配缓冲保存整段会话的样本；
 * 超过 {@link #SESSION_CAPACITY}（65536 帧）后隔点抽稀（stride 翻倍），
 * 均值仍按全部帧精确累计，只有百分位变成近似（日志里不区分，属于已知近似）。
 */
public final class ARBench {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** 单个窗口最多保存的样本数（8192 帧：60fps 下约 136 秒，远超默认窗口）。 */
    private static final int WINDOW_CAPACITY = 8192;

    /** 会话样本上限；满了以后隔点抽稀。 */
    private static final int SESSION_CAPACITY = 65536;

    /** 超过这个间隔的“帧”视为暂停/卡顿空档，不进统计（10 秒）。 */
    private static final long MAX_SAMPLE_NANOS = 10_000_000_000L;

    private static Boolean enabled;
    private static boolean initialized;
    private static long intervalNanos = ARSimArgs.BENCH_INTERVAL_DEFAULT_SECONDS * 1_000_000_000L;

    // ---- 窗口状态（热路径只碰这几个字段） ----
    /**
     * 窗口样本缓冲。**懒分配**（1.3.24 A7）：bench 关闭时不再静态占用这两个数组
     * （8192×8 + 65536×8 ≈ 576 KiB），只在 {@link #init()}（= 真的开启 bench）时才 new。
     */
    private static long[] window;
    private static int windowCount;
    private static long windowSumNanos;
    private static long windowDropped;
    private static long windowGaps;
    private static long windowStartNanos;

    /** 上一帧的 nanoTime；0 = 无基准（首帧/刚离开世界）。 */
    private static long lastFrameNanos;

    // ---- 会话状态 ----
    /** 会话样本缓冲；与 {@link #window} 同样懒分配（1.3.24 A7）。 */
    private static long[] session;
    private static int sessionCount;
    private static int sessionStride = 1;
    private static long sessionSumNanos;
    private static long sessionFrames;
    private static long sessionGaps;
    private static long sessionStartNanos;
    private static int sessionWindows;

    // ---- AR 实际 draw method 的反射句柄（只解析一次；AR 不在时为 null） ----
    private static Method arDrawMethod;
    private static boolean arDrawResolved;

    private ARBench() {
    }

    /** bench 是否开启（默认 false；未设置/非法值都按关处理）。 */
    public static boolean enabled() {
        Boolean cached = enabled;
        if (cached == null) {
            try {
                cached = Boolean.TRUE.equals(ARSimArgs.bench());
            } catch (Throwable t) {
                cached = Boolean.FALSE;
            }
            enabled = cached;
        }
        return cached;
    }

    /**
     * 注册事件与退出钩子。调用方必须先用 {@link #enabled()} 判定——
     * 不开时这个方法不会被调用，本类也就不会给游戏加任何东西。
     */
    public static void init() {
        try {
            if (initialized) {
                return;
            }
            initialized = true;
            // 1.3.24（A7）：样本缓冲懒分配——只有真的开启 bench 才占这 ~576 KiB。
            if (window == null) {
                window = new long[WINDOW_CAPACITY];
                session = new long[SESSION_CAPACITY];
            }
            int seconds = ARSimArgs.benchIntervalSeconds();
            intervalNanos = seconds * 1_000_000_000L;
            NeoForge.EVENT_BUS.addListener(ARBench::onRenderFramePre);
            NeoForge.EVENT_BUS.addListener(ARBench::onLoggingOut);
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> sessionSummary("game exit"), "arforcemobile-bench"));
            } catch (Throwable ignored) {
                // 关游戏总结拿不到就算了，绝不能因此让启动失败
            }
            LOGGER.info("[ARForceMobile] BENCH enabled: window={}s, sample=RenderFrameEvent.Pre (in-world frames only). "
                    + "One stats line per window + a session summary when leaving the world or closing the game. "
                    + "Override the window with -Darforcemobile.benchInterval=<seconds> (1..3600).", seconds);
        } catch (Throwable ignored) {
            // 静默降级
        }
    }

    /** RenderFrameEvent.Pre（每帧一次，世界内）——采样入口。 */
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        frame();
    }

    /** 退出世界（含退出单人存档）：打一行会话总结并开始新会话。 */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sessionSummary("world exit");
        // 1.3.24（A7）：退出世界立刻重置窗口（会话总结已打完并自行清零会话统计）。
        // 不重置的话，重进世界后的第一个 BENCH 窗口的 win= 时长会从上次进世界开始算。
        resetWindow();
    }

    /**
     * 一帧的采样。热路径：两次 nanoTime + 一次数组写入 + 若干计数，无分配、无排序。
     */
    public static void frame() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.level == null) {
                // 不在世界里：不采样，并清掉时间基准，避免进出世界时产生巨大空档。
                // 1.3.24（A7）：同时重置窗口——否则重进世界后的第一个 BENCH 窗口会把
                // 主菜单/加载界面的时间算进 win= 时长（正好污染 A/B 的第一段）。
                lastFrameNanos = 0L;
                resetWindow();
                return;
            }
            if (window == null || session == null) {
                return; // 未开启 bench（缓冲未分配）；理论上 frame() 只会在 init() 之后被注册
            }
            long now = System.nanoTime();
            long last = lastFrameNanos;
            lastFrameNanos = now;
            if (last == 0L) {
                return; // 第一帧只立基准
            }
            long delta = now - last;
            if (delta <= 0L) {
                return;
            }
            if (delta > MAX_SAMPLE_NANOS) {
                windowGaps++;
                sessionGaps++;
                return;
            }

            if (windowCount < WINDOW_CAPACITY) {
                window[windowCount++] = delta;
            } else {
                windowDropped++;
            }
            windowSumNanos += delta;
            if (windowStartNanos == 0L) {
                windowStartNanos = now;
            }

            if (sessionStartNanos == 0L) {
                sessionStartNanos = now;
            }
            sessionSumNanos += delta;
            sessionFrames++;
            if (sessionFrames % sessionStride == 0) {
                if (sessionCount < SESSION_CAPACITY) {
                    session[sessionCount++] = delta;
                } else {
                    compactSession();
                }
            }

            long elapsed = now - windowStartNanos;
            if (elapsed >= intervalNanos) {
                windowSummary(elapsed);
            }
        } catch (Throwable ignored) {
            // 静默降级：采样出错绝不影响渲染
        }
    }

    /** 窗口边界：排序 + 算分位 + 打一行日志，然后清窗口。 */
    private static void windowSummary(long elapsedNanos) {
        try {
            int count = windowCount;
            long dropped = windowDropped;
            long gaps = windowGaps;
            long sumNanos = windowSumNanos;
            sessionWindows++;
            if (count > 0) {
                Arrays.sort(window, 0, count);
                LOGGER.info("[ARForceMobile] BENCH win={}s frames={}{} gaps={} | {} | mcFps={} | state: {}",
                        secondsText(elapsedNanos), count, dropped > 0 ? "+" + dropped : "", gaps,
                        stats(sumNanos, count + dropped, window, count), mcFps(), state());
            }
        } catch (Throwable ignored) {
            // 静默降级
        } finally {
            resetWindow();
        }
    }

    /**
     * 会话总结（退出世界 / 关游戏）。没有新样本时什么都不打。
     * reason 只进日志文本，不影响统计。
     */
    public static void sessionSummary(String reason) {
        try {
            if (session == null || window == null) {
                return; // 未开启 bench（缓冲未分配）时什么都不打
            }
            int count = sessionCount;
            if (sessionFrames <= 0 || count <= 0) {
                resetSession();
                return;
            }
            Arrays.sort(session, 0, count);
            long durationNanos = sessionStartNanos == 0L ? 0L : System.nanoTime() - sessionStartNanos;
            LOGGER.info("[ARForceMobile] BENCH SESSION ({}): duration={}s frames={} windows={} gaps={} | {} | mcFps={} | state: {}",
                    reason, secondsText(durationNanos), sessionFrames, sessionWindows, sessionGaps,
                    stats(sessionSumNanos, sessionFrames, session, count), mcFps(), state());
        } catch (Throwable ignored) {
            // 静默降级（关游戏时日志系统可能已在拆除）
        } finally {
            resetSession();
        }
    }

    /**
     * 统计串：mean / median / 1% low / p95，毫秒与 fps 双单位。
     * {@code sorted} 必须是已升序排列的 {@code [0, count)}；均值用 {@code frameCount}
     * （可能大于 count：窗口超大时尾部样本没进数组，均值仍按全部帧算）。
     */
    private static String stats(long sumNanos, long frameCount, long[] sorted, int count) {
        double meanMs = frameCount > 0 ? sumNanos / (double) frameCount / 1_000_000.0 : 0.0;
        double medianMs = percentile(sorted, count, 0.50) / 1_000_000.0;
        double p95Ms = percentile(sorted, count, 0.95) / 1_000_000.0;
        int worst = Math.max(1, (int) Math.ceil(count * 0.01));
        long worstSum = 0L;
        for (int i = Math.max(0, count - worst); i < count; i++) {
            worstSum += sorted[i];
        }
        double worstMs = worstSum / (double) worst / 1_000_000.0;
        return "mean=" + msFps(meanMs)
                + " median=" + msFps(medianMs)
                + " 1%low=" + msFps(worstMs)
                + " p95=" + msFps(p95Ms);
    }

    /** 升序数组上取 q 分位（nearest-rank）。 */
    private static long percentile(long[] sorted, int count, double q) {
        if (count <= 0) {
            return 0L;
        }
        int index = (int) Math.ceil(q * count) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= count) {
            index = count - 1;
        }
        return sorted[index];
    }

    private static String msFps(double ms) {
        double fps = ms > 0.0 ? 1000.0 / ms : 0.0;
        return String.format(Locale.ROOT, "%.2fms/%.1ffps", ms, fps);
    }

    private static String secondsText(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000_000.0);
    }

    /** Minecraft 自己的 FPS 计数（F3 上那个），作为交叉校验；拿不到返回 -1。 */
    private static int mcFps() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null ? -1 : minecraft.getFps();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 当时的 AR 档位关键状态：mapMode / 是否客户端内存（tier-2）/ 绘制方法。
     * 只在窗口边界调用（每次调用都可能分配字符串），不进热路径。
     */
    private static String state() {
        StringBuilder builder = new StringBuilder(96);
        try {
            builder.append("mapMode:").append(ARMapMode.mode());
        } catch (Throwable t) {
            builder.append("mapMode:?");
        }
        try {
            builder.append(",clientMem:").append(ARClientBufferUploader.clientModeActive());
        } catch (Throwable t) {
            builder.append(",clientMem:?");
        }
        try {
            builder.append(",draw:").append(drawChoice());
        } catch (Throwable t) {
            builder.append(",draw:?");
        }
        String arDraw = arDrawMethodName();
        if (arDraw != null) {
            builder.append(",arDraw:").append(arDraw);
        }
        return builder.toString();
    }

    /**
     * 本 mod 会强制 AR 用哪个绘制方法（只读镜像 ForceBaseVertexMixin 的判定优先级，
     * 用于诊断；不改任何行为）。AR 自己最终用了哪个见 {@link #arDrawMethodName()}。
     */
    private static String drawChoice() {
        if (!ARForceEnvironment.isMobile()) {
            return "AR-default(non-mobile)";
        }
        String configured = ARForceConfig.drawMethod();
        if ("INDIRECT".equals(configured)) {
            return "INDIRECT(config)";
        }
        if ("BASEVERTEX".equals(configured)) {
            return "BASEVERTEX(config)";
        }
        if ("DEFAULT".equals(configured)) {
            return "AR-default(config)";
        }
        String forced = ARSimArgs.forceDrawMethod();
        if ("indirect".equals(forced)) {
            return "INDIRECT(prop)";
        }
        if ("basevertex".equals(forced)) {
            return "BASEVERTEX(prop)";
        }
        if ("default".equals(forced)) {
            return "AR-default(prop)";
        }
        if (ARForceConfig.safeDrawMethodOnClientBuffer() && ARClientBufferUploader.clientModeActive()) {
            return "INDIRECT(safegate)";
        }
        if (ARForceEnvironment.isMGL()) {
            return "INDIRECT(mgl)";
        }
        return "BASEVERTEX(mobile-default)";
    }

    /**
     * AR 运行期实际持有的 IDrawMethod 类名（反射取值，AR 不在/未初始化时为 null）。
     * 只解析一次 Class/Method，之后每次窗口边界一次 invoke——够便宜且绝不影响渲染。
     */
    private static String arDrawMethodName() {
        try {
            if (!arDrawResolved) {
                arDrawResolved = true;
                arDrawMethod = Class.forName("com.github.argon4w.acceleratedrendering.core.CoreFeature")
                        .getMethod("getDrawMethod");
            }
            if (arDrawMethod == null) {
                return null;
            }
            Object instance = arDrawMethod.invoke(null);
            return instance == null ? "null" : instance.getClass().getSimpleName();
        } catch (Throwable t) {
            arDrawMethod = null;
            return null;
        }
    }

    private static void resetWindow() {
        windowCount = 0;
        windowSumNanos = 0L;
        windowDropped = 0L;
        windowGaps = 0L;
        windowStartNanos = 0L;
    }

    private static void resetSession() {
        sessionCount = 0;
        sessionStride = 1;
        sessionSumNanos = 0L;
        sessionFrames = 0L;
        sessionGaps = 0L;
        sessionStartNanos = 0L;
        sessionWindows = 0;
    }

    /**
     * 会话样本缓冲写满：隔点抽稀（保留偶数位），stride 翻倍。
     * 均值始终按全部帧精确累计，抽稀只影响会话总结里的百分位精度。
     */
    private static void compactSession() {
        int half = sessionCount / 2;
        for (int i = 0; i < half; i++) {
            session[i] = session[i * 2];
        }
        sessionCount = half;
        if (sessionStride > 0 && sessionStride <= Integer.MAX_VALUE / 2) {
            sessionStride *= 2;
        }
    }
}
