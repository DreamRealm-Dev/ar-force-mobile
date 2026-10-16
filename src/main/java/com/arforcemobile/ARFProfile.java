package com.arforcemobile;

import java.util.Arrays;
import java.util.Locale;

/**
 * 帧阶段归因器（1.3.24 · N3）——<b>统计与格式化核心，零 MC/NeoForge/Mixin 依赖</b>。
 *
 * <p>背景：生产环境 {@code Minecraft.profiler = InactiveProfiler.INSTANCE}（Minecraft.java:388，
 * 只有 F3 的 profiler 图打开时才换成 ActiveProfiler，:1285-1329），所以 MC 自带的 profiler
 * 在手机上是<b>永远没有数据</b>的。本类提供自插桩所需的全部纯逻辑：
 * 阶段表、热路径 enter/exit、帧边界入库、窗口滚动、分位统计、固定字段输出。
 * 混入（mixin）只负责在正确的点调 {@link #enter(int)} / {@link #exit(int)}，
 * 所有算术都在这里，因此可以在没有游戏的 JVM 里用 javac+java 完整自测
 * （见 {@code investigation/H-profiler/harness}）。
 *
 * <h2>设计纪律</h2>
 * <ul>
 *   <li><b>默认关</b>：{@link #enabled} 为 false 时 {@link #frameTick(boolean)} 不会把
 *       {@link #active} 置真，全部钩子只剩一次静态布尔读取 + 分支（未走）；</li>
 *   <li><b>热路径零分配</b>：{@code enter/exit/claim/oitFrame/markHook/frameTick} 里没有
 *       new / 装箱 / 字符串拼接 / lambda 捕获 —— 由 harness 的 {@code javap -c} 自动断言；</li>
 *   <li><b>只在世界内、只在帧内</b>：{@link #frameTick(boolean)} 传 {@code inWorld}；
 *       帧外的钩子调用一律丢弃；</li>
 *   <li><b>异常静默降级</b>：所有入口 try/catch(Throwable)；不配对/漏 RETURN 的阶段会被
 *       自动发现（unclosed / dead），5 帧后停表，绝不让一个坏钩子把数据整片带偏；</li>
 *   <li><b>可采样</b>：{@code stride>1} 时只统计 1/N 帧，用 {@code frames=} 与
 *       {@code stride=} 明确写出，避免"数看不出问题"；</li>
 *   <li><b>自证</b>：钩子命中位图 {@code hooks=0x..} + 静默钩子名单 {@code silent=[]}，
 *       对抗 {@code defaultRequire:0} 的静默失效。</li>
 * </ul>
 *
 * <h2>统计口径</h2>
 * <ul>
 *   <li>每阶段累计 {@code sum[p]}（ns，含嵌套的 inclusive 口径：只有最外层一次配对才累加，
 *       同阶段重入不会重复计数）与 {@code calls[p]}（每次进入都计）；</li>
 *   <li>每帧末把 {@code sum[p] - frameBase[p]} 作为该帧该阶段的增量，写入样本环；</li>
 *   <li>均值 = {@code sum[p]/frames}；占比 = {@code sum[p]/sum[P_FRAME]*100}；</li>
 *   <li>「帧剩余部分」用差值补全：{@code world_rest = world - Σ(它的直接子阶段)}、
 *       {@code frame_rest = frame - Σ(直接子阶段)}、{@code outside = 帧周期 - frame}
 *       （帧周期来自 RenderFrameEvent.Pre 的相邻差，含 blit/flip/vsync/限帧/主循环 tick）。</li>
 * </ul>
 */
public final class ARFProfile {

    // ==================== 阶段表（顺序即输出字段顺序，固定不变） ====================

    /** 整帧 CPU 提交时间：GameRenderer.render(DeltaTracker, boolean) HEAD/RETURN（GameRenderer.java:1004）。 */
    public static final int P_FRAME = 0;
    /** 世界渲染：LevelRenderer.renderLevel(...)（LevelRenderer.java:921）。 */
    public static final int P_WORLD = 1;
    /** 可见性/区块编译排队：setupRender（:815，Sodium 用 @Overwrite 顶替为 SodiumWorldRenderer.setupTerrain）。 */
    public static final int P_TERRAIN_SETUP = 2;
    /** 天空：renderSky（:1598）。 */
    public static final int P_SKY = 3;
    /** 云：renderClouds（:1723）。 */
    public static final int P_CLOUDS = 4;
    /** 雨雪 + 世界边界：renderSnowAndRain（:266）+ renderWorldBorder（:2013）。 */
    public static final int P_WEATHER = 5;
    /** 地形 solid 层：SodiumWorldRenderer.drawChunkLayer / LevelRenderer.renderSectionLayer（:1280）。 */
    public static final int P_TERRAIN_SOLID = 6;
    /** 地形 cutoutMipped 层。 */
    public static final int P_TERRAIN_CUTOUT_MIPPED = 7;
    /** 地形 cutout 层。 */
    public static final int P_TERRAIN_CUTOUT = 8;
    /** 地形 translucent 层（tripwire 折入这里）。 */
    public static final int P_TERRAIN_TRANSLUCENT = 9;
    /** 其余 RenderType 层（Iris/其它 mod 新增的 pass；正常为 0）。 */
    public static final int P_TERRAIN_OTHER = 10;
    /** 实体渲染总和：EntityRenderDispatcher.render(...)（:142）。 */
    public static final int P_ENTITIES = 11;
    /** 其中 Create 机械（ContraptionEntity）的部分——Flywheel 接管时这里≈0。 */
    public static final int P_ENTITIES_CONTRAPTION = 12;
    /** Flywheel 视觉渲染：VisualizationManagerImpl.render(RenderContext)（含 OIT 全屏 pass 与几何提交）。 */
    public static final int P_FLYWHEEL = 13;
    /** Flywheel 每帧前置：VisualizationManagerImpl.beginFrame(RenderContext)（在 renderLevel HEAD 处）。 */
    public static final int P_FLYWHEEL_FRAME = 14;
    /** Flywheel 破坏进度：VisualizationManagerImpl.renderCrumbling(...)。 */
    public static final int P_FLYWHEEL_CRUMBLE = 15;
    /** 方块实体：BlockEntityRenderDispatcher.render(...)（:69；调用点 LevelRenderer.java:1082,1097）。 */
    public static final int P_BLOCKENTITIES = 16;
    /** 粒子：ParticleEngine.render(LightTexture, Camera, float, Frustum, Predicate)（:464）。 */
    public static final int P_PARTICLES = 17;
    /** 调试渲染：LevelRenderer.renderDebug（:1352）+ DebugRenderer.render（:101）。 */
    public static final int P_DEBUG = 18;
    /** 第一人称手部：ItemInHandRenderer.renderHandsWithItems（:328，在 GameRenderer.renderLevel 内）。 */
    public static final int P_HAND = 19;
    /** HUD：Gui.render(GuiGraphics, DeltaTracker)（Gui.java:257 → layerManager.render :261）。 */
    public static final int P_HUD = 20;
    /** 界面/遮罩：Screen.render（Screen.java:132）+ Overlay.render。 */
    public static final int P_SCREEN = 21;
    /** 后处理：PostChain.process(float)（GameRenderer.java:1037）+ entityEffect.process（LevelRenderer.java:1120）。 */
    public static final int P_POSTFX = 22;
    /** 自开销校准专用槽（不输出）。 */
    public static final int P_SELF = 23;

    public static final int PHASE_COUNT = 24;
    /** 实际输出的阶段数（不含 P_SELF）。 */
    public static final int REPORTED_COUNT = 23;

    /** 阶段名（固定字段名，改动即破坏 A/B 可比性）。 */
    public static final String[] NAMES = {
            "frame", "world", "terrain_setup", "sky", "clouds", "weather",
            "terrain_solid", "terrain_cutout_mipped", "terrain_cutout", "terrain_translucent", "terrain_other",
            "entities", "entities_contraption", "flywheel", "flywheel_frame", "flywheel_crumble",
            "blockentities", "particles", "debug", "hand", "hud", "screen", "postfx", "self"
    };

    /**
     * 父阶段（-1 = 顶层）。差值法算「未归因余量」用。
     *
     * 1.3.26 修正（task-22 理论验证）：`screen` 实际嵌在 `hud` 内
     * （Gui.java:248 注册 layer → :257→:261 渲染 → Screen#render），所以
     * PARENT[P_SCREEN] 必须是 P_HUD 而不是 0（frame），否则「frame 的直接子阶段」
     * 会把 screen 当成与 hud 并列的一层、frame_rest 被多减一次。
     */
    private static final int[] PARENT = {
            -1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 11, 1, 1, 1, 1, 1, 1, 0, 0, 20, 0, -1
    };

    /** 需要单独列出「直接子阶段」的父阶段（用于 world_rest / frame_rest / entities_other）。 */
    private static final int[] CHILDREN_OF_WORLD = {
            P_TERRAIN_SETUP, P_SKY, P_CLOUDS, P_WEATHER, P_TERRAIN_SOLID, P_TERRAIN_CUTOUT_MIPPED,
            P_TERRAIN_CUTOUT, P_TERRAIN_TRANSLUCENT, P_TERRAIN_OTHER, P_ENTITIES,
            P_FLYWHEEL, P_FLYWHEEL_FRAME, P_FLYWHEEL_CRUMBLE, P_BLOCKENTITIES, P_PARTICLES, P_DEBUG
    };
    /**
     * frame 的**直接**子阶段。1.3.26 修正（task-22）：去掉 P_SCREEN —— screen 嵌在 hud 里
     * （见 PARENT 注释），留着会与 hud 重复相减、把 frame_rest 系统性地算小。
     * screen 的时长仍单独上报（screen= 字段），只是不再进 frame_rest 的减项。
     */
    private static final int[] CHILDREN_OF_FRAME = { P_WORLD, P_HAND, P_HUD, P_POSTFX };
    private static final int[] TERRAIN_LAYERS = {
            P_TERRAIN_SOLID, P_TERRAIN_CUTOUT_MIPPED, P_TERRAIN_CUTOUT, P_TERRAIN_TRANSLUCENT, P_TERRAIN_OTHER
    };

    // ==================== 钩子位（自证用） ====================

    public static final int H_FRAME = 0;
    public static final int H_WORLD = 1;
    public static final int H_TERRAIN_SETUP_VANILLA = 2;
    public static final int H_TERRAIN_SETUP_SODIUM = 3;
    public static final int H_SKY = 4;
    public static final int H_CLOUDS = 5;
    public static final int H_WEATHER = 6;
    public static final int H_TERRAIN_VANILLA = 7;
    public static final int H_TERRAIN_SODIUM = 8;
    public static final int H_ENTITIES = 9;
    public static final int H_FLYWHEEL = 10;
    public static final int H_FLYWHEEL_FRAME = 11;
    public static final int H_FLYWHEEL_CRUMBLE = 12;
    public static final int H_BLOCKENTITIES = 13;
    public static final int H_PARTICLES = 14;
    public static final int H_DEBUG = 15;
    public static final int H_HAND = 16;
    public static final int H_HUD = 17;
    public static final int H_SCREEN = 18;
    public static final int H_POSTFX = 19;
    public static final int H_OIT_ACCESSOR = 20;
    public static final int HOOK_COUNT = 21;

    // ==================== 容量与阈值 ====================

    /** 每阶段每窗口样本上限（1024 帧 ≈ 60fps 下 17s，够默认 10s 窗口）。 */
    private static final int SAMPLE_CAPACITY = 1024;
    /** 帧周期/帧总时长样本上限（1% low 用，样本多才有意义）。 */
    private static final int FRAME_SAMPLE_CAPACITY = 4096;
    /** 超过这个间隔的「帧」视为暂停/空档，丢弃并计入 gaps（与 ARBench 同口径，10 秒）。 */
    private static long maxGapNanos = 10_000_000_000L;
    /** 同一阶段连续多少帧没等到 exit 就判定钩子已死（停表，不再污染其它数据）。 */
    private static final int DEAD_AFTER_FRAMES = 5;

    // ==================== 状态 ====================

    /**
     * 是否启用（由 -Darforcemobile.profile 决定；装配层在首帧前设置）。
     * 1.3.28：改 volatile —— 它在装配线程写、渲染线程读（钩子首行），non-volatile 只是靠
     * 事件注册的 happens-before 侥幸成立；volatile 读的代价在"默认关"路径上也可忽略。
     */
    public static volatile boolean enabled;
    /** 本帧是否在统计（热路径唯一门槛；帧外调用一律丢弃）。 */
    static boolean active;

    private static int stride = 1;
    private static long intervalNanos = 10_000_000_000L;
    private static Output sink = line -> { };

    /** 自会话开始（或上次 testReset）的累计值：只用于「每帧增量」的差值与帧边界快照。 */
    private static final long[] sum = new long[PHASE_COUNT];
    /** 本窗口内的累计值：窗口行/差值法/自开销占比的唯一数据源。 */
    private static final long[] winSum = new long[PHASE_COUNT];
    private static final int[] calls = new int[PHASE_COUNT];
    /**
     * 1.3.29（task-37 I-2）：<b>本窗口</b>的调用计数。窗口行的 {@code xN} 用它算
     * （{@code winCalls[phase]/windowFrames}），窗口边界归零；会话累计的 {@code calls}
     * 仍保留，只用于 {@code xNall=} 与自开销（{@code self=}）。
     */
    private static final int[] winCalls = new int[PHASE_COUNT];
    private static final int[] openDepth = new int[PHASE_COUNT];
    private static final long[] openStart = new long[PHASE_COUNT];
    private static final int[] openFrames = new int[PHASE_COUNT];
    private static boolean[] dead = new boolean[PHASE_COUNT];
    private static final long[] frameBase = new long[PHASE_COUNT];
    private static final long[] maxNanos = new long[PHASE_COUNT];
    private static final int[] owner = new int[PHASE_COUNT];

    private static final long[][] samples = new long[PHASE_COUNT][SAMPLE_CAPACITY];
    private static final int[] sampleCount = new int[PHASE_COUNT];
    private static final int[] samplePhaseSeq = new int[PHASE_COUNT];

    private static final long[] frameSamples = new long[FRAME_SAMPLE_CAPACITY];
    private static final long[] periodSamples = new long[FRAME_SAMPLE_CAPACITY];
    private static int frameSampleCount;
    private static int periodSampleCount;
    private static int frameSampleSeq;
    private static int periodSampleSeq;

    private static final long[] sessionSum = new long[PHASE_COUNT];
    /** 1.3.29（task-37 I-5）：剔除预热窗后的会话累计（用于 SESSION 的 steady 组）。 */
    private static final long[] steadySum = new long[PHASE_COUNT];
    /** 会话级帧时样本：all = 含预热；steady = 剔除预热（median/1%low 用 steady）。 */
    private static final long[] sessionAllFrameSamples = new long[FRAME_SAMPLE_CAPACITY];
    private static final long[] sessionSteadyFrameSamples = new long[FRAME_SAMPLE_CAPACITY];
    private static int sessionAllFrameCount;
    private static int sessionSteadyFrameCount;
    private static int sessionAllFrameSeq;
    private static int sessionSteadyFrameSeq;

    private static int windowFrames;
    private static long windowStartNanos;
    private static long windowGaps;
    private static long sessionFrames;
    private static long sessionGaps;
    private static long sessionPeriodSum;
    private static long sessionPeriodCount;
    private static long lastFramePreNanos;
    private static int globalFrameSeq;
    private static int unclosedNow;
    private static long anomalies;
    private static long nanGuard;
    private static long oitFrames;
    private static long oitOffSum;
    private static long oitOffFrames;
    private static long oitOnSum;
    private static long oitOnFrames;
    private static boolean oitFlag;
    private static long selfCostNanos;
    private static int hookMask;

    // ---- 1.3.29（task-37）：预热标记 + 边界强制闭合 ----
    /** 入世界后前 N 秒视为预热（{@code -Darforcemobile.profileWarmup=<秒>}，默认 20）。 */
    private static long warmupNanos = 20_000_000_000L;
    /** 本次入世界的时刻（0 = 当前不在世界/尚未入世界）。 */
    private static long worldEnterNanos;
    /** 本窗口是否落在预热期内（窗口行 {@code warmup=1}）。 */
    private static boolean windowWarmup;
    private static long warmupFrames;
    private static long warmupWindows;
    /** 边界强制闭合次数（窗口/会话边界发现仍未配对的阶段；正常应为 0）。 */
    private static long boundaryFlushes;

    /** 上一窗口两行的副本（自测断言用；只在窗口边界写，热路径不碰）。 */
    public static final String[] lastLines = new String[2];

    private ARFProfile() {
    }

    /** 日志出口（生产是 slf4j，自测是内存/抛异常的假实现）。 */
    public interface Output {
        void line(String message);
    }

    // ==================== 装配 ====================

    /**
     * 配置。{@code windowSeconds} ≤0 或 >3600 回落到 10；{@code everyFrames} ≤1 表示每帧都统计。
     * 必须在第一帧之前调用（客户端线程）。
     */
    public static void configure(int windowSeconds, int everyFrames, Output output) {
        try {
            intervalNanos = (windowSeconds >= 1 && windowSeconds <= 3600 ? windowSeconds : 10) * 1_000_000_000L;
            stride = everyFrames >= 1 ? everyFrames : 1;
            sink = output == null ? line -> { } : output;
            windowStartNanos = 0L;
            lastFramePreNanos = 0L;
            calibrateSelfCost();
        } catch (Throwable ignored) {
            // 静默降级：配置失败=不统计，绝不影响渲染
        }
    }

    public static int stride() {
        return stride;
    }

    /**
     * 1.3.29（task-37 I-5）：设置预热期（秒）。≤0 表示不做预热标记。
     * 由 {@link ARFProfilerBootstrap} 在 {@link #configure} 之前调用。
     */
    public static void setWarmupSeconds(int seconds) {
        try {
            warmupNanos = seconds > 0 ? (long) seconds * 1_000_000_000L : 0L;
        } catch (Throwable ignored) {
            warmupNanos = 20_000_000_000L;
        }
    }

    public static int warmupSeconds() {
        return (int) (warmupNanos / 1_000_000_000L);
    }

    public static long selfCostNanos() {
        return selfCostNanos;
    }

    public static int hookMask() {
        return hookMask;
    }

    /** 自开销校准：用 {@link #P_SELF} 槽跑 20 万次真实 enter/exit 配对，取平均。 */
    private static void calibrateSelfCost() {
        try {
            final int iterations = 200_000;
            sum[P_SELF] = 0L;
            openDepth[P_SELF] = 0;
            active = true;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                enter(P_SELF);
                exit(P_SELF);
            }
            long wall = System.nanoTime() - start;
            sum[P_SELF] = 0L;
            calls[P_SELF] = 0;
            openDepth[P_SELF] = 0;
            frameBase[P_SELF] = 0L;
            active = false;
            // 口径：整段循环的墙钟时间 / 迭代次数 = 一对 enter+exit 的完整成本（含循环开销，保守）。
            // 不要用内部记录到的 (exit_ts - enter_ts) 求和：那只是配对的一部分，
            // 实测 52ns/对 的墙钟对应内部和只有 12~26ns（JIT 把两次 nanoTime 拉近了）。
            selfCostNanos = wall / iterations;
        } catch (Throwable ignored) {
            selfCostNanos = -1L;
        }
    }

    // ==================== 热路径（零分配，javap 断言） ====================

    /** 记录钩子命中（每次进入钩子首行调用；1 次 int 或运算 + 静态写）。 */
    public static void markHook(int hookId) {
        if (hookId >= 0 && hookId < 32) {
            hookMask |= (1 << hookId);
        }
    }

    /**
     * 同一阶段有多个候选注入点时（例：地形 = Sodium 的 drawChunkLayer 或原版的 renderSectionLayer），
     * 先到先得，避免两处同时统计导致双计。返回 true 表示本次可以统计。
     */
    public static boolean claim(int hookId, int phase) {
        if (!active) {
            return false;
        }
        int current = owner[phase];
        if (current == 0) {
            owner[phase] = hookId + 1;
            return true;
        }
        return current == hookId + 1;
    }

    /** 进入阶段。热路径：静态布尔读 + 分支 + 数组自增 + （最外层时）一次 nanoTime。 */
    public static void enter(int phase) {
        if (!active) {
            return;
        }
        if (phase < 0 || phase >= PHASE_COUNT) {
            return;
        }
        if (openDepth[phase] == 0) {
            openStart[phase] = System.nanoTime();
        }
        openDepth[phase]++;
        calls[phase]++;
        winCalls[phase]++;
    }

    /** 退出阶段。inclusive 口径：只有最外层一次配对才累加时长。 */
    public static void exit(int phase) {
        if (!active) {
            return;
        }
        if (phase < 0 || phase >= PHASE_COUNT) {
            return;
        }
        int depth = openDepth[phase] - 1;
        if (depth < 0) {
            openDepth[phase] = 0;
            anomalies++;
            return;
        }
        openDepth[phase] = depth;
        if (depth == 0) {
            long delta = System.nanoTime() - openStart[phase];
            if (delta > 0L) {
                sum[phase] += delta;
            }
        }
    }

    /** 通知本帧是否走了 OIT 路径（由 InstancedDrawManager 的 oitDraws 访问器提供）。 */
    public static void oitFrame(boolean activeOit) {
        if (!active) {
            return;
        }
        oitFlag = activeOit;
        if (activeOit) {
            oitFrames++;
        }
    }

    // ==================== 帧边界 ====================

    /**
     * 每帧一次（RenderFrameEvent.Pre）。{@code inWorld=false} 时停表并丢弃时间基准，
     * 避免菜单/加载界面的帧混进 A/B。
     */
    public static void frameTick(boolean inWorld) {
        try {
            if (!enabled || !inWorld) {
                active = false;
                worldEnterNanos = 0L; // 1.3.29（I-5）：离开世界 -> 下次入世界重新计预热
                if (lastFramePreNanos != 0L) {
                    lastFramePreNanos = 0L;
                    endFrame();
                }
                return;
            }
            long now = System.nanoTime();
            long last = lastFramePreNanos;
            lastFramePreNanos = now;
            if (last == 0L) {
                windowStartNanos = now;
                worldEnterNanos = now; // 1.3.29（I-5）：入世界时刻 = 预热起点
                active = true;
                return;
            }
            endFrame();
            long period = now - last;
            if (period <= 0L || period > maxGapNanos) {
                windowGaps++;
                sessionGaps++;
            } else {
                sessionPeriodSum += period;
                sessionPeriodCount++;
                if (periodSampleCount < FRAME_SAMPLE_CAPACITY) {
                    periodSamples[periodSampleCount++] = period;
                } else if ((++periodSampleSeq & 1) == 0) {
                    periodSampleCount = decimate(periodSamples, FRAME_SAMPLE_CAPACITY);
                }
            }
            if (windowStartNanos == 0L) {
                windowStartNanos = now;
            }
            if (now - windowStartNanos >= intervalNanos) {
                rollWindow(now);
            }
            globalFrameSeq++;
            active = stride <= 1 || (globalFrameSeq % stride == 0);
        } catch (Throwable ignored) {
            active = false;
        }
    }

    /**
     * 上一帧的阶段增量入库（帧边界调用，允许分配，但本方法不分配）。
     * 同时做漏 RETURN 的对账：连续 {@link #DEAD_AFTER_FRAMES} 帧未闭合的阶段判定为死钩子并停表。
     */
    private static void endFrame() {
        long now = System.nanoTime();
        boolean counted = active;
        active = false;
        unclosedNow = 0;
        boolean warm = isWarmup(now); // 1.3.29（I-5）：本帧是否落在入世界后的预热期内
        for (int p = 0; p < PHASE_COUNT; p++) {
            if (openDepth[p] > 0) {
                unclosedNow++;
                if (++openFrames[p] <= DEAD_AFTER_FRAMES) {
                    long delta = now - openStart[p];
                    if (delta > 0L) {
                        sum[p] += delta;
                    }
                } else {
                    dead[p] = true;
                }
                openDepth[p] = 0;
            } else {
                openFrames[p] = 0;
            }
        }
        if (!counted) {
            return;
        }
        windowFrames++;
        sessionFrames++;
        long frameDelta = 0L;
        long flywheelDelta = 0L;
        for (int p = 0; p < REPORTED_COUNT; p++) {
            long delta = sum[p] - frameBase[p];
            frameBase[p] = sum[p];
            sessionSum[p] += delta;
            winSum[p] += delta < 0L ? 0L : delta;
            if (!warm) {
                steadySum[p] += delta < 0L ? 0L : delta;
            }
            if (p == P_FLYWHEEL) {
                flywheelDelta = delta > 0L ? delta : 0L;
            }
            if (dead[p]) {
                continue;
            }
            if (delta < 0L) {
                delta = 0L;
                anomalies++;
            }
            if (delta > maxNanos[p]) {
                maxNanos[p] = delta;
            }
            if (sampleCount[p] < SAMPLE_CAPACITY) {
                samples[p][sampleCount[p]++] = delta;
            } else if ((++samplePhaseSeq[p] & 1) == 0) {
                sampleCount[p] = decimate(samples[p], SAMPLE_CAPACITY);
            }
            if (p == P_FRAME) {
                frameDelta = delta;
            }
        }
        if (frameDelta > 0L) {
            if (frameSampleCount < FRAME_SAMPLE_CAPACITY) {
                frameSamples[frameSampleCount++] = frameDelta;
            }
            // 1.3.29（I-5）：会话级两组样本 —— all（含预热）与 steady（剔除预热，median/1%low 用它）
            if (sessionAllFrameCount < FRAME_SAMPLE_CAPACITY) {
                sessionAllFrameSamples[sessionAllFrameCount++] = frameDelta;
            } else if ((++sessionAllFrameSeq & 1) == 0) {
                sessionAllFrameCount = decimate(sessionAllFrameSamples, FRAME_SAMPLE_CAPACITY);
            }
            if (!warm) {
                if (sessionSteadyFrameCount < FRAME_SAMPLE_CAPACITY) {
                    sessionSteadyFrameSamples[sessionSteadyFrameCount++] = frameDelta;
                } else if ((++sessionSteadyFrameSeq & 1) == 0) {
                    sessionSteadyFrameCount = decimate(sessionSteadyFrameSamples, FRAME_SAMPLE_CAPACITY);
                }
            }
        }
        if (warm) {
            warmupFrames++;
        }
        if (oitFlag) {
            oitOnSum += flywheelDelta;
            oitOnFrames++;
        } else {
            oitOffSum += flywheelDelta;
            oitOffFrames++;
        }
        oitFlag = false;
    }

    /** 隔点抽稀（保留偶数位），返回抽稀后的样本数。均值仍按 sum/calls 精确，只有分位变成近似。 */
    private static int decimate(long[] array, int capacity) {
        int half = capacity / 2;
        for (int i = 0; i < half; i++) {
            array[i] = array[i * 2];
        }
        for (int i = half; i < capacity; i++) {
            array[i] = 0L;
        }
        return half;
    }

    // ==================== 窗口/会话输出 ====================

    private static void rollWindow(long now) {
        try {
            // 1.3.29（I-3）：窗口边界强制闭合未配对阶段，时间不再漏进下一窗
            flushOpen(now);
            windowWarmup = isWarmup(now);
            if (windowWarmup) {
                warmupWindows++;
            }
            if (windowFrames > 0 || windowGaps > 0) {
                String[] lines = buildWindowLines(now - windowStartNanos);
                lastLines[0] = lines[0];
                lastLines[1] = lines[1];
                sink.line(lines[0]);
                sink.line(lines[1]);
            }
        } catch (Throwable ignored) {
            // 静默降级
        } finally {
            resetWindow();
            windowStartNanos = now;
        }
    }

    /** 会话总结（退出世界/关游戏）。只给均值，不保留全量样本（内存换确定性）。 */
    public static void summarize(String reason) {
        try {
            flushOpen(System.nanoTime()); // 1.3.29（I-3）：会话边界强制闭合
            if (sessionFrames <= 0) {
                resetSession();
                return;
            }
            StringBuilder builder = new StringBuilder(256);
            builder.append("[ARForceMobile] PROFILE SESSION (").append(reason).append("): frames=").append(sessionFrames)
                    .append(" gaps=").append(sessionGaps).append(" stride=").append(stride)
                    .append(" |");
            long frameMean = sessionSum[P_FRAME] / Math.max(1L, sessionFrames);
            for (int p = 0; p < REPORTED_COUNT; p++) {
                appendMs(builder, NAMES[p], sessionSum[p] / Math.max(1L, sessionFrames));
            }
            builder.append(" | outside=").append(ms(sessionPeriodCount > 0 ? sessionPeriodSum / sessionPeriodCount : 0L));
            builder.append(" frameMean=").append(ms(frameMean));
            builder.append(" ms/frame(avg)");
            // 1.3.29（task-37 I-5）：同时给"含预热"与"剔除预热"两组；median/1%low 用剔除预热的。
            long steadyFrames = Math.max(0L, sessionFrames - warmupFrames);
            builder.append(" | steady: frames=").append(steadyFrames)
                    .append(" warmupFrames=").append(warmupFrames)
                    .append(" warmupWindows=").append(warmupWindows)
                    .append(" warmupSec=").append(warmupSeconds());
            builder.append(" | steady frame: ").append(dist(sessionSteadyFrameSamples, sessionSteadyFrameCount));
            builder.append(" | all frame: ").append(dist(sessionAllFrameSamples, sessionAllFrameCount));
            if (steadyFrames > 0) {
                builder.append(" | steady ms/frame:");
                for (int p = 0; p < REPORTED_COUNT; p++) {
                    appendMs(builder, NAMES[p], steadySum[p] / steadyFrames);
                }
            }
            sink.line(builder.toString());
        } catch (Throwable ignored) {
            // 静默降级（关游戏时日志系统可能已在拆除）
        } finally {
            resetSession();
        }
    }

    private static String[] buildWindowLines(long elapsedNanos) {
        long frames = Math.max(1L, windowFrames);
        long frame = winSum[P_FRAME] / frames;
        StringBuilder first = new StringBuilder(512);
        first.append("[ARForceMobile] PROFILE win=").append(seconds(elapsedNanos)).append("s frames=").append(windowFrames);
        first.append(" gaps=").append(windowGaps).append(" stride=").append(stride)
                .append(" unclosed=").append(unclosedNow).append(" nanGuard=").append(nanGuard)
                .append(" | ms/frame: ");
        for (int p = 0; p < REPORTED_COUNT; p++) {
            appendMsPct(first, NAMES[p], winSum[p] / frames, frame, p);
            if (p == P_TERRAIN_OTHER) {
                first.append(" terrain=").append(ms(childSum(TERRAIN_LAYERS, frames)));
            }
        }
        first.append(" | rest: world_rest=").append(ms(rest(P_WORLD, CHILDREN_OF_WORLD, frames)))
                .append(" frame_rest=").append(ms(rest(P_FRAME, CHILDREN_OF_FRAME, frames)))
                .append(" entities_other=").append(ms(rest(P_ENTITIES, new int[] { P_ENTITIES_CONTRAPTION }, frames)));
        // 1.3.29（task-37 I-1）：把"父项 vs 直接子项之和"显式打出来。overrun>0 表示当前
        // 计时口径下 Σ子项 > frame（父子区间不自洽）——此时"占 frame 百分比"不可信，
        // 要看 overrun 与 over1x（哪些阶段本窗口每帧被进入 ≥2 次）定位根因。
        long kids = childSum(CHILDREN_OF_FRAME, frames);
        long overrun = kids - frame;
        first.append(" | children: sum=").append(ms(kids))
                .append(" overrun=").append(ms(overrun))
                .append('(').append(pct(overrun, frame)).append("%)")
                .append(" frameCalls=").append(winCalls[P_FRAME])
                .append(" worldCalls=").append(winCalls[P_WORLD])
                .append(" hudCalls=").append(winCalls[P_HUD])
                .append(" handCalls=").append(winCalls[P_HAND])
                .append(" over1x=").append(over1x(frames));
        first.append(" warmup=").append(windowWarmup ? 1 : 0);

        StringBuilder second = new StringBuilder(512);
        second.append("[ARForceMobile] PROFILE dist win=").append(seconds(elapsedNanos)).append("s | frame: ")
                .append(dist(frameSamples, frameSampleCount));
        second.append(" | outside: ").append(periodDist());
        second.append(" | flywheel: oitFrames=").append(oitFrames)
                .append(" oitOn=").append(meanish(oitOnSum, oitOnFrames))
                .append(" oitOff=").append(meanish(oitOffSum, oitOffFrames));
        second.append(" | hooks=0x").append(Integer.toHexString(hookMask));
        // 1.3.26 修正（task-22）：静默清单拆两栏——required 非空 = 插桩本身没接上，
        // 此时不要拿数字下结论；optional 是条件钩子（只在特定场景/装特定 mod 时才该响）。
        second.append(" silent_required=").append(silentRequired());
        second.append(" silent_optional=").append(silentOptional());
        // 1.3.29（task-37 I-2）：会话累计的"每帧调用次数"另给一个字段（xN 已改为本窗口径）
        second.append(" | xNall=").append(xNall(frames));
        second.append(" | self=").append(selfLine(frames));
        return new String[] { first.toString(), second.toString() };
    }

    /** 直接子阶段之和。 */
    private static long childSum(int[] children, long frames) {
        long total = 0L;
        for (int i = 0; i < children.length; i++) {
            total += winSum[children[i]] / frames;
        }
        return total;
    }

    /** 差值法余量（负值按 0 处理，并计入 anomalies，避免出现负的"剩余"）。 */
    private static long rest(int parent, int[] children, long frames) {
        long value = winSum[parent] / frames - childSum(children, frames);
        if (value < 0L) {
            anomalies++;
            return 0L;
        }
        return value;
    }

    private static void appendMs(StringBuilder builder, String name, long nanos) {
        builder.append(' ').append(name).append('=').append(ms(nanos));
    }

    private static void appendMsPct(StringBuilder builder, String name, long nanos, long frame, int phase) {
        if (dead[phase]) {
            builder.append(' ').append(name).append("=na");
            return;
        }
        builder.append(' ').append(name).append('=').append(ms(nanos));
        if (phase != P_FRAME) {
            builder.append('(').append(pct(nanos, frame)).append("%/x").append(xRate(phase)).append(')');
        }
    }

    private static String dist(long[] values, int count) {
        if (count <= 0) {
            return "mean=0.00 median=0.00 p95=0.00 1%low=0.00 max=0.00 ms";
        }
        long[] copy = new long[count];
        System.arraycopy(values, 0, copy, 0, count);
        Arrays.sort(copy);
        long total = 0L;
        for (int i = 0; i < count; i++) {
            total += copy[i];
        }
        int worst = Math.max(1, (int) Math.ceil(count * 0.01));
        long worstSum = 0L;
        for (int i = count - worst; i < count; i++) {
            worstSum += copy[i];
        }
        return "mean=" + ms(total / count)
                + " median=" + ms(percentile(copy, 0.50))
                + " p95=" + ms(percentile(copy, 0.95))
                + " 1%low=" + ms(worstSum / worst)
                + " max=" + ms(copy[count - 1]) + " ms";
    }

    private static String periodDist() {
        return dist(periodSamples, periodSampleCount);
    }

    /** 升序数组上的 nearest-rank 分位（与 ARBench.percentile 同口径）。 */
    public static long percentile(long[] sortedAscending, double q) {
        int count = sortedAscending.length;
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
        return sortedAscending[index];
    }

    /** 必响钩子组：组内任一成员命中即视为接上（地形是原版/Sodium 双候选，只能响一个）。 */
    private static final int[][] REQUIRED_GROUPS = {
            { H_FRAME }, { H_WORLD },
            { H_TERRAIN_SETUP_VANILLA, H_TERRAIN_SETUP_SODIUM },
            { H_TERRAIN_VANILLA, H_TERRAIN_SODIUM },
            { H_ENTITIES }, { H_FLYWHEEL }, { H_FLYWHEEL_FRAME },
            { H_BLOCKENTITIES }, { H_PARTICLES }, { H_HUD }, { H_HAND }
    };

    /**
     * 条件钩子：只在该场景/该 mod 存在时才该响（静默不算故障）。
     * 注：entities_contraption 是**阶段**（P_ENTITIES_CONTRAPTION，见 ms/frame 行），
     * 不是独立钩子位——它由 H_ENTITIES 那个钩子按 Create 软判定分流，所以这里不列。
     */
    private static final int[] OPTIONAL_HOOKS = {
            H_DEBUG, H_SCREEN, H_FLYWHEEL_CRUMBLE, H_POSTFX, H_OIT_ACCESSOR,
            // 1.3.29（task-37 I-4）：H_CLOUDS 曾经既不在 required 也不在 optional，
            // 于是"云钩子从未命中"在两栏里都看不见（实测 hooks=0xfffdf 恒缺 bit5）。
            H_CLOUDS,
            // 同一个自检（uncoveredHooks()）还抓到 H_SKY 与 H_WEATHER 也漏登记：
            // 无天空的维度/被 shader 接管的场景不该要求 sky 响；天气只有在下雨下雪时才响。
            H_SKY,
            H_WEATHER
    };

    /**
     * required 栏：组内成员全都没命中才列出来（= 插桩没接上）。
     * 地形双候选里"被 Sodium @Overwrite 顶掉的那一半"天然静默，不算 required 缺失。
     */
    private static String silentRequired() {
        StringBuilder builder = new StringBuilder(32);
        builder.append('[');
        boolean first = true;
        for (int g = 0; g < REQUIRED_GROUPS.length; g++) {
            int[] group = REQUIRED_GROUPS[g];
            boolean hit = false;
            for (int i = 0; i < group.length; i++) {
                if ((hookMask & (1 << group[i])) != 0) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(hookName(group[0]));
                first = false;
            }
        }
        builder.append(']');
        return builder.toString();
    }

    /** optional 栏：条件钩子里没命中的（silent_required 为空时这些可以解释成场景不适用）。 */
    private static String silentOptional() {
        StringBuilder builder = new StringBuilder(32);
        builder.append('[');
        boolean first = true;
        for (int i = 0; i < OPTIONAL_HOOKS.length; i++) {
            int hook = OPTIONAL_HOOKS[i];
            if ((hookMask & (1 << hook)) == 0) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(hookName(hook));
                first = false;
            }
        }
        builder.append(']');
        return builder.toString();
    }

    private static String hookName(int hookId) {
        switch (hookId) {
            case H_FRAME: return "frame";
            case H_WORLD: return "world";
            case H_TERRAIN_SETUP_VANILLA: return "setup_vanilla";
            case H_TERRAIN_SETUP_SODIUM: return "setup_sodium";
            case H_SKY: return "sky";
            case H_CLOUDS: return "clouds";
            case H_WEATHER: return "weather";
            case H_TERRAIN_VANILLA: return "terrain_vanilla";
            case H_TERRAIN_SODIUM: return "terrain_sodium";
            case H_ENTITIES: return "entities";
            case H_FLYWHEEL: return "flywheel";
            case H_FLYWHEEL_FRAME: return "flywheel_frame";
            case H_FLYWHEEL_CRUMBLE: return "flywheel_crumble";
            case H_BLOCKENTITIES: return "blockentities";
            case H_PARTICLES: return "particles";
            case H_DEBUG: return "debug";
            case H_HAND: return "hand";
            case H_HUD: return "hud";
            case H_SCREEN: return "screen";
            case H_POSTFX: return "postfx";
            case H_OIT_ACCESSOR: return "oit_accessor";
            default: return "h" + hookId;
        }
    }

    private static String selfLine(long frames) {
        if (selfCostNanos < 0L) {
            return "n/a";
        }
        long pairs = 0L;
        for (int p = 0; p < REPORTED_COUNT; p++) {
            pairs += calls[p] / frames;
        }
        long perFrame = pairs * selfCostNanos;
        long frame = winSum[P_FRAME] / frames;
        return selfCostNanos + "ns/pair " + (perFrame / 1000L) + "us/frame " + pct(perFrame, frame) + "% of frame";
    }

    /** 1.3.29（I-5）：是否处于入世界后的预热期。 */
    private static boolean isWarmup(long now) {
        return warmupNanos > 0L && worldEnterNanos != 0L && now - worldEnterNanos < warmupNanos;
    }

    /**
     * 1.3.29（I-3）：把仍未配对的阶段按当前时刻结算。{@code unclosed} 仍由 {@code endFrame}
     * 计数；这里只保证"跨窗口/跨会话的时间"不会漏进下一次统计（边界不变量可被自测断言）。
     */
    private static void flushOpen(long now) {
        for (int p = 0; p < PHASE_COUNT; p++) {
            if (openDepth[p] > 0) {
                long delta = now - openStart[p];
                if (delta > 0L) {
                    sum[p] += delta;
                }
                openDepth[p] = 0;
                boundaryFlushes++;
            }
        }
    }

    /** 1.3.29（I-1）：本窗口每帧被进入 ≥2 次的阶段（多进入 = 计时口径嫌疑，用来定位 overrun）。 */
    private static String over1x(long frames) {
        StringBuilder builder = new StringBuilder(24);
        builder.append('[');
        boolean first = true;
        for (int p = 0; p < REPORTED_COUNT; p++) {
            if (winCalls[p] / frames >= 2L) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(NAMES[p]).append(" x").append(winCalls[p] / frames);
                first = false;
            }
        }
        builder.append(']');
        return builder.toString();
    }

    /** 1.3.29（I-2）：会话累计的每帧调用次数（frame 的直接子项），与窗口 {@code xN} 对比用。 */
    private static String xNall(long frames) {
        long denom = Math.max(1L, sessionFrames);
        return "[frame " + (calls[P_FRAME] / denom)
                + ",world " + (calls[P_WORLD] / denom)
                + ",hand " + (calls[P_HAND] / denom)
                + ",hud " + (calls[P_HUD] / denom)
                + ",postfx " + (calls[P_POSTFX] / denom) + ']';
    }

    /** 钩子是否既不在 REQUIRED_GROUPS 也不在 OPTIONAL_HOOKS（I-4 的"漏登记"自检，正常应为 []）。 */
    public static String uncoveredHooks() {
        StringBuilder builder = new StringBuilder(24);
        builder.append('[');
        boolean first = true;
        for (int h = 0; h < HOOK_COUNT; h++) {
            boolean listed = false;
            for (int g = 0; g < REQUIRED_GROUPS.length && !listed; g++) {
                for (int i = 0; i < REQUIRED_GROUPS[g].length; i++) {
                    if (REQUIRED_GROUPS[g][i] == h) {
                        listed = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < OPTIONAL_HOOKS.length && !listed; i++) {
                if (OPTIONAL_HOOKS[i] == h) {
                    listed = true;
                }
            }
            if (!listed) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(hookName(h));
                first = false;
            }
        }
        builder.append(']');
        return builder.toString();
    }

    // ---- 自测/诊断 getters（1.3.29） ----
    public static int windowCalls(int phase) {
        return phase >= 0 && phase < PHASE_COUNT ? winCalls[phase] : 0;
    }

    public static long warmupFrames() {
        return warmupFrames;
    }

    public static long warmupWindows() {
        return warmupWindows;
    }

    public static long boundaryFlushes() {
        return boundaryFlushes;
    }

    public static boolean lastWindowWarmup() {
        return windowWarmup;
    }

    /**
     * 1.3.29（task-37 I-2）：{@code xN=} 现在是<b>本窗口</b>的平均每帧进入次数
     * （{@code winCalls/windowFrames}）。会话累计的每帧次数见 {@code xNall=}。
     * 1.3.28 及以前这里用会话累计 {@code calls}，导致 xN 随窗口单调增大（实测 world x1→x6）。
     */
    private static long xRate(int phase) {
        return winCalls[phase] / Math.max(1L, windowFrames);
    }

    private static String meanish(long total, long count) {
        return ms(count > 0 ? total / count : 0L);
    }

    private static String ms(long nanos) {
        return fmt(nanos / 1_000_000.0);
    }

    private static String pct(long part, long whole) {
        return fmt(whole > 0L ? 100.0 * part / whole : 0.0);
    }

    private static String seconds(long nanos) {
        return fmt(nanos / 1_000_000_000.0);
    }

    /** 非有限值守卫：任何 NaN/Inf 都写成 0.00 并计数（nanGuard 字段），保证输出行永远可解析。 */
    private static String fmt(double value) {
        if (!isFinite(value)) {
            nanGuard++;
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    // ==================== 复位 ====================

    private static void resetWindow() {
        for (int p = 0; p < PHASE_COUNT; p++) {
            frameBase[p] = sum[p];
            winSum[p] = 0L;
            winCalls[p] = 0;
            sampleCount[p] = 0;
            samplePhaseSeq[p] = 0;
            maxNanos[p] = 0L;
        }
        windowFrames = 0;
        windowGaps = 0L;
        frameSampleCount = 0;
        periodSampleCount = 0;
        frameSampleSeq = 0;
        periodSampleSeq = 0;
        unclosedNow = 0;
        oitFrames = 0L;
        oitOnSum = 0L;
        oitOnFrames = 0L;
        oitOffSum = 0L;
        oitOffFrames = 0L;
        windowWarmup = false;
    }

    private static void resetSession() {
        for (int p = 0; p < PHASE_COUNT; p++) {
            sessionSum[p] = 0L;
            steadySum[p] = 0L;
        }
        sessionFrames = 0L;
        sessionGaps = 0L;
        sessionPeriodSum = 0L;
        sessionPeriodCount = 0L;
        sessionAllFrameCount = 0;
        sessionSteadyFrameCount = 0;
        sessionAllFrameSeq = 0;
        sessionSteadyFrameSeq = 0;
        warmupFrames = 0L;
        warmupWindows = 0L;
        worldEnterNanos = 0L;
    }

    // ==================== 自测/诊断入口（不参与热路径） ====================

    /**
     * 直接注入合成增量（<b>只给离线 harness 用</b>，生产代码不得调用）。
     * 走的是与真实钩子同一套累加器，因此窗口/分位/格式化逻辑是同一份代码。
     */
    public static void testAccumulate(int phase, long nanos, int callCount) {
        if (phase < 0 || phase >= PHASE_COUNT) {
            return;
        }
        sum[phase] += nanos;
        calls[phase] += callCount;
        if (nanos > maxNanos[phase]) {
            maxNanos[phase] = nanos;
        }
    }

    /** 覆盖空档阈值（只给离线 harness 用：10 秒没法在自测里真等）。 */
    public static void testSetMaxGapNanos(long nanos) {
        maxGapNanos = nanos > 0L ? nanos : 10_000_000_000L;
    }

    /** 强制结束当前帧并入库（只给离线 harness 用，等价于一次 RenderFrameEvent.Pre）。 */
    public static void testEndFrame() {
        endFrame();
    }

    /** 把 active 置真（只给离线 harness 用：跳过 frameTick 的门槛）。 */
    public static void testSetActive(boolean value) {
        active = value;
    }

    /** 直接触发窗口输出（只给离线 harness 用）。 */
    public static void testRollWindow() {
        if (windowStartNanos == 0L) {
            windowStartNanos = System.nanoTime() - 10_000_000_000L;
        }
        rollWindow(System.nanoTime());
    }

    /** 让窗口行的 win= 显示成指定秒数（只给离线 harness 用；合成路径没有真实窗口起点）。 */
    public static void testSetWindowElapsed(long nanos) {
        windowStartNanos = System.nanoTime() - Math.max(0L, nanos);
    }

    public static long phaseSumNanos(int phase) {
        return phase >= 0 && phase < PHASE_COUNT ? sum[phase] : 0L;
    }

    public static int phaseCalls(int phase) {
        return phase >= 0 && phase < PHASE_COUNT ? calls[phase] : 0;
    }

    public static long anomalies() {
        return anomalies;
    }

    public static long nanGuardCount() {
        return nanGuard;
    }

    public static boolean isDead(int phase) {
        return phase >= 0 && phase < PHASE_COUNT && dead[phase];
    }

    public static int openDepth(int phase) {
        return phase >= 0 && phase < PHASE_COUNT ? openDepth[phase] : 0;
    }

    /** 清空全部统计状态（自测用；生产只在会话总结后由 resetSession 做局部复位）。 */
    public static void testReset() {
        for (int p = 0; p < PHASE_COUNT; p++) {
            sum[p] = 0L;
            calls[p] = 0;
            winCalls[p] = 0;
            openDepth[p] = 0;
            openStart[p] = 0L;
            openFrames[p] = 0;
            dead[p] = false;
            owner[p] = 0;
            maxNanos[p] = 0L;
            frameBase[p] = 0L;
        }
        for (int p = 0; p < PHASE_COUNT; p++) {
            winSum[p] = 0L;
        }
        resetWindow();
        resetSession();
        hookMask = 0;
        globalFrameSeq = 0;
        lastFramePreNanos = 0L;
        active = false;
        oitFlag = false;
        anomalies = 0L;
        nanGuard = 0L;
        boundaryFlushes = 0L;
        windowStartNanos = 0L;
        lastLines[0] = null;
        lastLines[1] = null;
    }
}
