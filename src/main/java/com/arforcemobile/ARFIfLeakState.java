package com.arforcemobile;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L5：AR x ImmediatelyFast 泄漏修复的运行时状态 + 自证指标（task-32）。
 *
 * 三条计数器（热路径只有 AtomicLong.incrementAndGet）：
 * - WRAPPERS：IF 的 BatchingBuffers\$WrappedRenderLayer 构造次数；
 * - CALLS：AR AcceleratedBufferSource#getBuffer 的键规范化注入点被调用次数；
 * - CANON：其中"键确实可规范化"（参数 instanceof ARFOriginalRenderType 且 original != null）的次数。
 *
 * 修复开关优先级（后者覆盖前者）：
 *   1) 系统属性 -Darforcemobile.ifKeyFix=true|false（默认 true，保持 1.3.29-ifleak 语义）；
 *   2) 运行时覆盖 /arforce ifleak fix on|off（本类 fixRuntimeOverride，null = 跟随属性）；
 *   A/B/A/B 自动分相（ARFIfLeakAutoRunner）就是反复设置/清掉这个运行时覆盖。
 *
 * 直接指标 arBuilderTableSize()：反射读 AR 的 CoreBuffers 静态 AcceleratedBufferSource 字段，
 * 再反射读其私有 activeBuilders 的 size() 求和。泄漏时该值随帧单调增长；修复生效后应趋于常数。
 *
 * L9（task-36）修复「真机上恒为 0」：
 *   1) 类加载改用**游戏 classloader**（context loader -> Minecraft 的 loader -> 本类 loader -> 系统 loader
 *      依次尝试）。本 mod 的类与 AR 可能由不同模块加载器加载，用本类 loader 的 Class.forName 会找不到 AR；
 *   2) 区分「失败」与「空」：类/字段解析失败、或所有 source 都读不出来 -> 返回 **-1**；
 *      解析成功但表确实为空 -> 返回 0；
 *   3) 解析成功/失败各打一次日志：成功 IFLEAK ar-table resolved: <n> sources；
 *      失败 WARN 带原因 + CoreBuffers 静态字段清单（一眼分辨 classloader 还是字段名问题）；
 *   4) 解析失败的缓存会**重试**（不再永久缓存空结果）；/arforce ifleak status 里附每个 source 的 size。
 *
 * 日志（默认关，零开销）：
 * - -Darforcemobile.leakWatch=true 时每 30 秒一行 LEAK-WATCH（格式与 1.3.29-ifleak 基线一致）；
 * - 命令/分相触发时一行 IFLEAK + statusLine()。
 */
public final class ARFIfLeakState {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final AtomicLong WRAPPERS = new AtomicLong();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong CANON = new AtomicLong();

    /** null = 跟随 -Darforcemobile.ifleakFix；非 null = /arforce ifleak fix 的运行时覆盖。 */
    private static volatile Boolean fixRuntimeOverride;

    /**
     * 修复的系统属性默认值：-Darforcemobile.ifKeyFix=false 关（默认 true）。
     * 属性名沿用 IfLayerKeyCanonicalizeMixin 1.3.29-ifleak 基线里的既有名字（Lead 真机 A/B 在用），
     * 不要改成别的名字；运行时开关用 /arforce ifleak fix on|off。
     */
    private static final boolean FIX_PROPERTY_DEFAULT =
            !"false".equalsIgnoreCase(System.getProperty("arforcemobile.ifKeyFix", "true"));

    /** 观测仪开关：只在类初始化时读一次系统属性。 */
    private static final boolean WATCH = Boolean.getBoolean("arforcemobile.leakWatch");

    private static final long WINDOW_NANOS = 30_000_000_000L;
    private static final AtomicLong WINDOW_START_NANOS = new AtomicLong();
    private static final AtomicLong WINDOW_START_WRAPPERS = new AtomicLong();

    /** AR 侧反射缓存：非空 = 已解析成功；null/空数组 = 尚未解析成功（每次都重试）。 */
    private static volatile Field[] arSourceFields;

    /** 最近一次解析/读取的明细（进 statusLine）：如 "8/8 [BLOCK=3, ENTITY=9, ...]"。 */
    private static volatile String arSourceDetail = "unresolved";

    /** 一次性日志开关：成功/失败各打一次，避免刷屏。 */
    private static volatile boolean arResolveLoggedOk;
    private static volatile boolean arResolveLoggedFail;

    private static final String AR_CORE_BUFFERS = "com.github.argon4w.acceleratedrendering.core.CoreBuffers";
    private static final String AR_SOURCE_FQN =
            "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedBufferSource";
    private static final String AR_SOURCE_SIMPLE = "AcceleratedBufferSource";
    private static final String AR_ACTIVE_BUILDERS = "activeBuilders";

    private ARFIfLeakState() {
    }

    // ==================== 开关 ====================

    public static boolean fixEnabled() {
        Boolean override = fixRuntimeOverride;
        return override != null ? override : FIX_PROPERTY_DEFAULT;
    }

    /** /arforce ifleak fix on|off：设置运行时覆盖（立即生效，重启回属性/默认）。 */
    public static void setFixRuntime(boolean enabled) {
        fixRuntimeOverride = enabled;
    }

    /** 清掉运行时覆盖，回到 -Darforcemobile.ifKeyFix/默认值。 */
    public static void clearFixRuntime() {
        fixRuntimeOverride = null;
    }

    /** 计数器归零 + 清运行时覆盖（/arforce ifleak reset）。 */
    public static void reset() {
        clearFixRuntime();
        WRAPPERS.set(0L);
        CALLS.set(0L);
        CANON.set(0L);
        WINDOW_START_NANOS.set(0L);
        WINDOW_START_WRAPPERS.set(0L);
    }

    public static boolean watchEnabled() {
        return WATCH;
    }

    // ==================== 计数器 ====================

    public static long wrappers() {
        return WRAPPERS.get();
    }

    public static long calls() {
        return CALLS.get();
    }

    public static long canon() {
        return CANON.get();
    }

    public static void countCall() {
        CALLS.incrementAndGet();
    }

    public static void countCanonicalized() {
        CANON.incrementAndGet();
    }

    /** IF wrapper 构造热路径（仅在 watchEnabled() 时被调用）：计数 + 30 秒一次 LEAK-WATCH。 */
    public static void onWrapperConstructed() {
        long total = WRAPPERS.incrementAndGet();
        long now = System.nanoTime();
        long windowStart = WINDOW_START_NANOS.get();
        if (windowStart == 0L) {
            WINDOW_START_NANOS.set(now);
            WINDOW_START_WRAPPERS.set(total);
            return;
        }
        if (now - windowStart < WINDOW_NANOS) {
            return;
        }
        if (!WINDOW_START_NANOS.compareAndSet(windowStart, now)) {
            return;
        }
        long delta = total - WINDOW_START_WRAPPERS.getAndSet(total);
        // 与 1.3.29-ifleak 基线完全同格式，便于跨 jar 对齐 A/B 曲线
        LOGGER.info("[ARForceMobile] LEAK-WATCH wrappers={} (+{}) heapUsed={} max={} rss={}",
                total, delta, heapUsedMb(), maxHeapMb(), rssMb());
    }

    // ==================== 指标 ====================

    /**
     * AR 侧全部静态 source 的 activeBuilders.size() 之和。
     * 返回 -1 = 解析失败（类/字段读不到，或所有 source 都读失败）；0 = 解析成功但表确实为空。
     */
    public static int arBuilderTableSize() {
        Field[] fields = arSourceFields;
        if (fields == null || fields.length == 0) {
            fields = resolveArSourceFields();
            if (fields.length > 0) {
                arSourceFields = fields; // 只在成功时缓存；失败每次都重试（AR 可能加载得比我们晚）
            }
        }
        if (fields.length == 0) {
            arSourceDetail = "resolve-failed";
            return -1;
        }

        int total = 0;
        int readable = 0;
        StringBuilder detail = new StringBuilder();
        for (Field field : fields) {
            String name = field.getName();
            try {
                Object source = field.get(null);
                if (source == null) {
                    detail.append(name).append("=null,");
                    continue;
                }
                Field mapField = findDeclaredField(source.getClass(), AR_ACTIVE_BUILDERS);
                if (mapField == null) {
                    detail.append(name).append("=no-").append(AR_ACTIVE_BUILDERS).append(',');
                    continue;
                }
                mapField.setAccessible(true);
                Object map = mapField.get(source);
                if (!(map instanceof Map<?, ?> builders)) {
                    detail.append(name).append("=not-a-map,");
                    continue;
                }
                int size = builders.size();
                total += size;
                readable++;
                detail.append(name).append('=').append(size).append(',');
            } catch (Throwable t) {
                detail.append(name).append("=err,");
            }
        }
        arSourceDetail = readable + "/" + fields.length + " [" + detail + "]";
        return readable == 0 ? -1 : total; // 全部读失败 = 解析失败，不要伪装成"表为空"
    }

    /** 在类自身及其父类里找字段（AR 版本差异时保底）。 */
    private static Field findDeclaredField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /**
     * 用游戏 classloader 解析 CoreBuffers 的静态 AcceleratedBufferSource 字段。
     * 解析失败返回空数组（调用方据此返回 -1 并下次重试），并打一次 WARN（带静态字段清单）。
     */
    private static Field[] resolveArSourceFields() {
        Class<?> coreBuffers = null;
        Throwable lastError = null;
        String usedLoader = null;
        for (ClassLoader loader : candidateLoaders()) {
            try {
                coreBuffers = Class.forName(AR_CORE_BUFFERS, false, loader);
                usedLoader = describeLoader(loader);
                break;
            } catch (Throwable t) {
                lastError = t;
            }
        }
        if (coreBuffers == null) {
            if (!arResolveLoggedFail) {
                arResolveLoggedFail = true;
                LOGGER.warn("[ARForceMobile] IFLEAK ar-table resolve FAILED: class {} not found via "
                        + "context/Minecraft/own/system loader -> arBuilders=-1 (cause: {})",
                        AR_CORE_BUFFERS, String.valueOf(lastError));
            }
            return new Field[0];
        }

        List<Field> exact = new ArrayList<>();
        List<Field> bySimpleName = new ArrayList<>();
        StringBuilder inventory = new StringBuilder();
        for (Field field : coreBuffers.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            inventory.append(field.getName()).append(':').append(field.getType().getSimpleName()).append(' ');
            String typeName = field.getType().getName();
            if (AR_SOURCE_FQN.equals(typeName)) {
                exact.add(field);
            } else if (AR_SOURCE_SIMPLE.equals(field.getType().getSimpleName())) {
                bySimpleName.add(field);
            }
        }
        List<Field> found = !exact.isEmpty() ? exact : bySimpleName;
        for (Field field : found) {
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                // 单个字段设不了 accessible 时后面读取会失败并计入 detail
            }
        }

        if (found.isEmpty()) {
            if (!arResolveLoggedFail) {
                arResolveLoggedFail = true;
                LOGGER.warn("[ARForceMobile] IFLEAK ar-table resolve FAILED: {} loaded via {} but has no "
                        + "{} static field (static fields seen: {}) -> arBuilders=-1",
                        AR_CORE_BUFFERS, usedLoader, AR_SOURCE_SIMPLE, inventory);
            }
            return new Field[0];
        }

        if (!arResolveLoggedOk) {
            arResolveLoggedOk = true;
            LOGGER.info("[ARForceMobile] IFLEAK ar-table resolved: {} sources (exact={}, bySimpleName={}, loader={}, "
                    + "activeBuilders={})", found.size(), exact.size(), bySimpleName.size(), usedLoader,
                    findDeclaredField(found.get(0).getType(), AR_ACTIVE_BUILDERS) != null);
        }
        return found.toArray(new Field[0]);
    }

    /** 依次尝试的 classloader：context -> Minecraft 的（游戏）/本类 -> 系统。 */
    private static List<ClassLoader> candidateLoaders() {
        List<ClassLoader> loaders = new ArrayList<>(4);
        addLoader(loaders, contextClassLoader());
        addLoader(loaders, gameClassLoader());
        addLoader(loaders, ARFIfLeakState.class.getClassLoader());
        addLoader(loaders, ClassLoader.getSystemClassLoader());
        return loaders;
    }

    private static ClassLoader contextClassLoader() {
        try {
            return Thread.currentThread().getContextClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassLoader gameClassLoader() {
        try {
            return net.minecraft.client.Minecraft.class.getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addLoader(List<ClassLoader> loaders, ClassLoader loader) {
        if (loader != null && !loaders.contains(loader)) {
            loaders.add(loader);
        }
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        String name = loader.getClass().getName();
        ClassLoader parent = null;
        try {
            parent = loader.getParent();
        } catch (Throwable ignored) {
            // 忽略
        }
        return parent == null ? name : name + " <- " + parent.getClass().getName();
    }

    public static String statusLine() {
        int arBuilders = arBuilderTableSize(); // 同时刷新 arSourceDetail（-1 = 解析失败）
        return "fix=" + fixEnabled()
                + " wrappers=" + wrappers()
                + " calls=" + calls()
                + " canon=" + canon()
                + " arBuilders=" + arBuilders
                + " arTable=" + arSourceDetail
                + " heapUsed=" + heapUsedMb()
                + " rss=" + rssMb();
    }

    /** 命令/分相触发的一行 IFLEAK 日志（不节流：由调用方决定频率）。 */
    public static void logStatus(String tag) {
        LOGGER.info("[ARForceMobile] IFLEAK {} {}", tag, statusLine());
    }

    public static long heapUsedMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
    }

    public static long maxHeapMb() {
        return Runtime.getRuntime().maxMemory() / 1048576L;
    }

    /** RSS：Linux/Android 读 /proc/self/statm；其它平台退回进程 committed size（反射，避免编译期依赖）。 */
    public static long rssMb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/statm"))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 1) {
                    long pages = Long.parseLong(parts[1]);
                    return pages * 4096L / 1048576L;
                }
            }
        } catch (Throwable ignored) {
            // 非 Linux/Android：走下面的兜底
        }
        try {
            Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            Object value = bean.getClass().getMethod("getCommittedVirtualMemorySize").invoke(bean);
            if (value instanceof Long committed && committed > 0L) {
                return committed / 1048576L;
            }
        } catch (Throwable ignored) {
            // 无此 API：返回 -1，日志里如实显示
        }
        return -1L;
    }
}
