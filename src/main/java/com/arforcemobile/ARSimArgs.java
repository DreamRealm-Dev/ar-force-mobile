package com.arforcemobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模拟/调试参数：全部通过 JVM 参数提供，不需要改任何配置文件
 * （既不用动 config/arforcemobile-client.toml，也不用动 /sdcard/MG/config.json）。
 *
 * 设计纪律：
 * - 未设置任何参数时，所有查询返回 null / AUTO，调用方必须回退到原逻辑：
 *   默认行为与 1.3.17 完全一致；
 * - 只读 JVM 属性，不依赖 Minecraft / NeoForge / LWJGL，可独立编译与单测（见 fix/sim-selftest）。
 *
 * 属性一览（前缀 arforcemobile.）：
 *   simulate=<preset>                         一档一条参数；显式属性优先于 preset
 *   simProbe=auto|all-ok|persistent-fail|plain-fail|all-fail|fail
 *   simRenderer=<串>                           覆盖环境判定读到的 GL_RENDERER（spoof）；none/real 表示不覆盖
 *   mapMode=auto|tier1|client|native
 *   drawMethod=AUTO|BASEVERTEX|INDIRECT|DEFAULT  最高优先级，压过配置文件
 *   forceDrawMethod=indirect|basevertex|default
 *   mobileAutoEnable=true|false                覆盖 mobile_auto_enable
 *   clientBufferFallback=true|false            覆盖 client_buffer_fallback
 *   safeDrawMethodOnClientBuffer=true|false    覆盖 safe_draw_method_on_client_buffer
 *   bench=true|false                           帧时基准开关（默认 false：不开时行为与 1.3.22 完全一致）
 *   benchInterval=<秒>                          基准窗口长度，默认 10 秒（1..3600，非法值忽略并记入 problems）
 *   probeStorage=auto|mutable|immutable             探针存储分支覆盖（诊断用；auto = 镜像运行期真实分支，native 是 immutable 的别名）
 *   probeV1=true|false                              true = 用 1.3.24 的旧探针口径（默认 false = 1.3.25 新口径）
 *   profile=true|false                              帧阶段归因器（1.3.26 W3，默认 false：不开时零输出）
 *   profileInterval=<秒>                            归因器窗口长度，默认 10（1..3600，非法值忽略）
 *   profileStride=<N>                              归因器每 N 帧统计一次，默认 1（1..64，非法值忽略）
 */
public final class ARSimArgs {

    public static final String PREFIX = "arforcemobile.";

    public static final String PROBE_AUTO = "AUTO";
    public static final String PROBE_ALL_OK = "ALL_OK";
    public static final String PROBE_PERSISTENT_FAIL = "PERSISTENT_FAIL";
    public static final String PROBE_PLAIN_FAIL = "PLAIN_FAIL";
    public static final String PROBE_ALL_FAIL = "ALL_FAIL";

    public static final String LIST_PRESET = "list";

    private static final Map<String, Map<String, String>> PRESETS = createPresets();
    private static final List<String> PROBLEMS = new ArrayList<>();

    private ARSimArgs() {
    }

    private static Map<String, String> preset(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Map<String, String>> createPresets() {
        Map<String, Map<String, String>> presets = new LinkedHashMap<>();
        // ---- 610（两映射探针全灭）三档 ----
        presets.put("610-ar-off", preset("simProbe", "all-fail", "clientBufferFallback", "false", "mobileAutoEnable", "true"));
        // 610-* 档同时带 mapMode=client：simProbe 只改「决策层」（门禁/档位选择），
        // 健康设备的真实 GL 映射调用仍会成功（持久映射 NULL 后降级重试成功 = tier-1），
        // 所以要让「执行层」也真的走客户端内存，必须显式强制 mapMode=client。
        presets.put("610-tier2", preset("simProbe", "all-fail", "clientBufferFallback", "true", "mapMode", "client", "safeDrawMethodOnClientBuffer", "true", "forceDrawMethod", "indirect"));
        presets.put("610-crash", preset("simProbe", "all-fail", "clientBufferFallback", "true", "mapMode", "client", "safeDrawMethodOnClientBuffer", "false", "drawMethod", "BASEVERTEX", "forceDrawMethod", "basevertex"));
        // ---- 映射档位 ----
        presets.put("tier1", preset("mapMode", "tier1"));
        presets.put("tier0", preset("mapMode", "native"));
        presets.put("tier2", preset("mapMode", "client", "safeDrawMethodOnClientBuffer", "true", "forceDrawMethod", "indirect"));
        presets.put("tier2-crash", preset("mapMode", "client", "safeDrawMethodOnClientBuffer", "false", "drawMethod", "BASEVERTEX", "forceDrawMethod", "basevertex"));
        // ---- 其它 ----
        // tier2-ssbo：在 MobileGlues 上**唯一能跑起来**的 tier-2 组合。
        // 原因：INDIRECT 会编译 AR 的 atomic-counter culling compute shader，
        // MG 2.x 编不过（atomicAdd 重载不匹配）-> 崩；必须开 SSBO 重写把它改写成 SSBO 原子操作。
        presets.put("tier2-ssbo", preset("mapMode", "client", "safeDrawMethodOnClientBuffer", "true", "forceDrawMethod", "indirect", "enableSSBOFix", "true"));
        presets.put("610-tier2-ssbo", preset("simProbe", "all-fail", "clientBufferFallback", "true", "mapMode", "client", "safeDrawMethodOnClientBuffer", "true", "forceDrawMethod", "indirect", "enableSSBOFix", "true"));
        presets.put("ar-off", preset("mobileAutoEnable", "false"));
        presets.put("spoof610", preset("simRenderer", "Adreno (TM) 610 | OpenGL ES 3.2"));
        presets.put("spoof730", preset("simRenderer", "Adreno (TM) 730 | OpenGL ES 3.2"));
        return presets;
    }

    public static String presetNames() {
        return String.join(", ", PRESETS.keySet());
    }

    /** 全部规范属性名（PREFIX 之后的部分）。 */
    private static final String[] KEYS = {
        "simulate", "simProbe", "simRenderer", "mapMode", "drawMethod", "forceDrawMethod",
        "mobileAutoEnable", "clientBufferFallback", "safeDrawMethodOnClientBuffer", "enableSSBOFix",
        "bench", "benchInterval", "probeStorage", "probeV1",
        "profile", "profileInterval", "profileStride"
    };

    /** 小写写法 → 规范名。兼容历史文档里出现过的别名（例如早期提案写的 simulateProbe）。 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("simulateprobe", "simProbe");
        ALIASES.put("simprobe", "simProbe");
        ALIASES.put("simrenderer", "simRenderer");
        ALIASES.put("mapmode", "mapMode");
        ALIASES.put("drawmethod", "drawMethod");
        ALIASES.put("forcedrawmethod", "forceDrawMethod");
        ALIASES.put("mobileautoenable", "mobileAutoEnable");
        ALIASES.put("clientbufferfallback", "clientBufferFallback");
        ALIASES.put("safedrawmethodonclientbuffer", "safeDrawMethodOnClientBuffer");
        ALIASES.put("simulate", "simulate");
        ALIASES.put("enablessbofix", "enableSSBOFix");
        ALIASES.put("enablessbo", "enableSSBOFix");
        ALIASES.put("ssbo", "enableSSBOFix");
        ALIASES.put("bench", "bench");
        ALIASES.put("benchinterval", "benchInterval");
        ALIASES.put("probestorage", "probeStorage");
        ALIASES.put("probev1", "probeV1");
        ALIASES.put("profile", "profile");
        ALIASES.put("profileinterval", "profileInterval");
        ALIASES.put("profilewindow", "profileInterval");
        ALIASES.put("profilestride", "profileStride");
    }

    /** 本 JVM 里实际存在的所有 arforcemobile.* 属性：后缀（原样）→ 完整属性名。 */
    private static volatile Map<String, String> provided;

    private static Map<String, String> providedProperties() {
        Map<String, String> cached = provided;
        if (cached != null) {
            return cached;
        }
        Map<String, String> map = new LinkedHashMap<>();
        try {
            for (String name : System.getProperties().stringPropertyNames()) {
                if (name.startsWith(PREFIX) && name.length() > PREFIX.length()) {
                    map.put(name.substring(PREFIX.length()), name);
                }
            }
        } catch (Throwable ignored) {
            // 属性表不可读时保持空表
        }
        provided = map;
        return map;
    }

    /** 找出与规范键对应的属性名：精确匹配 > 大小写不敏感 / 别名。 */
    private static String matchedProperty(String canonicalKey) {
        Map<String, String> map = providedProperties();
        if (map.containsKey(canonicalKey)) {
            return map.get(canonicalKey);
        }
        String target = canonicalKey.toLowerCase(Locale.ROOT);
        List<String> aliases = new ArrayList<>();
        for (Map.Entry<String, String> entry : ALIASES.entrySet()) {
            if (entry.getValue().equals(canonicalKey)) {
                aliases.add(entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (lower.equals(target) || aliases.contains(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 未被识别的 arforcemobile.* 属性（含拼写建议）；没有则返回 null。 */
    public static String unknownReport() {
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> entry : providedProperties().entrySet()) {
            String suffix = entry.getKey();
            String lower = suffix.toLowerCase(Locale.ROOT);
            boolean known = ALIASES.containsKey(lower);
            if (!known) {
                for (String key : KEYS) {
                    if (key.toLowerCase(Locale.ROOT).equals(lower)) {
                        known = true;
                        break;
                    }
                }
            }
            if (!known) {
                unknown.add(PREFIX + suffix + "=" + System.getProperty(entry.getValue()) + suggest(lower));
            }
        }
        return unknown.isEmpty() ? null : String.join("; ", unknown);
    }

    private static String suggest(String lower) {
        int probe = Math.min(4, lower.length());
        if (probe > 0) {
            String head = lower.substring(0, probe);
            for (String key : KEYS) {
                String canonical = key.toLowerCase(Locale.ROOT);
                if (canonical.startsWith(head) || head.startsWith(canonical.substring(0, Math.min(probe, canonical.length())))) {
                    return " (unknown; did you mean " + PREFIX + key + "? known: " + String.join(", ", KEYS) + ")";
                }
            }
        }
        return " (unknown; known: " + String.join(", ", KEYS) + ")";
    }

    /**
     * 常开诊断（1.3.19）：把本 JVM 实际收到的 -D 参数 + 所有 arforcemobile.* 属性原样打出来。
     * 用途：判断「参数到底有没有到达 JVM」「有没有被启动器吞掉/改写」。
     * 不依赖任何 -D 参数本身（否则就成了鸡生蛋）。java.management 若在裁剪版 JRE 里缺失，
     * 会被捕获并降级为只打印属性表。
     */
    public static String jvmReport() {
        StringBuilder builder = new StringBuilder();
        builder.append("-D args: ");
        try {
            java.util.List<String> inputs = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> filtered = new ArrayList<>();
            for (String arg : inputs) {
                if (arg.startsWith("-XX:")) {
                    continue;
                }
                if (arg.startsWith("-Djava.class.path")) {
                    filtered.add("-Djava.class.path=<hidden>");
                    continue;
                }
                if (arg.startsWith("-Djava.home") || arg.startsWith("-Djava.io.tmpdir")) {
                    continue;
                }
                filtered.add(arg.length() > 160 ? arg.substring(0, 160) + "..." : arg);
            }
            builder.append(filtered.isEmpty() ? "(none)" : String.join(" ", filtered));
        } catch (Throwable t) {
            builder.append("<unavailable: ").append(t.getClass().getSimpleName()).append(">");
        }
        builder.append(" | arforcemobile.* props: ");
        List<String> props = new ArrayList<>();
        try {
            for (String name : System.getProperties().stringPropertyNames()) {
                if (name.startsWith(PREFIX)) {
                    props.add(name + "=" + System.getProperty(name));
                }
            }
        } catch (Throwable ignored) {
            // 属性表不可读时保持空表
        }
        Collections.sort(props);
        builder.append(props.isEmpty() ? "(none)" : String.join(", ", props));
        String unknown = unknownReport();
        if (unknown != null) {
            builder.append(" | unknown: ").append(unknown);
        }
        return builder.toString();
    }

    private static void problem(String message) {
        if (!PROBLEMS.contains(message)) {
            PROBLEMS.add(message);
        }
    }

    public static List<String> problems() {
        return Collections.unmodifiableList(new ArrayList<>(PROBLEMS));
    }

    private static String rawProperty(String key) {
        String name = matchedProperty(key);
        if (name == null) {
            return null;
        }
        // matchedProperty 返回的是**完整**属性名（含 PREFIX），不要再拼一次前缀。
        String fullName = name.startsWith(PREFIX) ? name : PREFIX + name;
        String value = System.getProperty(fullName);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** 已解析的 simulate 预设名（小写）；未设置/非法返回 null；list/help 返回 "list"。 */
    public static String preset() {
        String raw = rawProperty("simulate");
        if (raw == null) {
            return null;
        }
        String name = raw.toLowerCase(Locale.ROOT);
        if (name.equals("list") || name.equals("help") || name.equals("?")) {
            return LIST_PRESET;
        }
        if (!PRESETS.containsKey(name)) {
            problem("unknown simulate preset '" + raw + "' (available: " + presetNames() + ")");
            return null;
        }
        return name;
    }

    /** 解析后的取值：显式属性 > preset；都没有返回 null。 */
    public static String value(String key) {
        String property = rawProperty(key);
        if (property != null) {
            return property;
        }
        String name = preset();
        if (name == null || LIST_PRESET.equals(name)) {
            return null;
        }
        Map<String, String> map = PRESETS.get(name);
        return map == null ? null : map.get(key);
    }

    /** 取值来源："prop" / "preset:<名字>" / null。 */
    public static String source(String key) {
        if (rawProperty(key) != null) {
            return "prop";
        }
        String name = preset();
        if (name != null && !LIST_PRESET.equals(name)) {
            Map<String, String> map = PRESETS.get(name);
            if (map != null && map.containsKey(key)) {
                return "preset:" + name;
            }
        }
        return null;
    }

    /** 三态布尔：非法值记入 problems 并返回 null（调用方回退到原逻辑）。 */
    public static Boolean triBool(String key) {
        String value = value(key);
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true":
            case "1":
            case "on":
            case "yes":
            case "enable":
            case "enabled":
                return Boolean.TRUE;
            case "false":
            case "0":
            case "off":
            case "no":
            case "disable":
            case "disabled":
                return Boolean.FALSE;
            default:
                problem("invalid boolean for " + PREFIX + key + "='" + value + "' (use true|false)");
                return null;
        }
    }

    public static String simProbe() {
        String raw = value("simProbe");
        if (raw == null) {
            return PROBE_AUTO;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto":
            case "real":
            case "none":
            case "off":
                return PROBE_AUTO;
            case "ok":
            case "all-ok":
            case "allok":
            case "pass":
                return PROBE_ALL_OK;
            case "persistent-fail":
            case "persistentfail":
            case "no-persistent":
                return PROBE_PERSISTENT_FAIL;
            case "plain-fail":
            case "plainfail":
            case "no-plain":
                return PROBE_PLAIN_FAIL;
            case "fail":
            case "all-fail":
            case "allfail":
            case "both-fail":
            case "610":
                return PROBE_ALL_FAIL;
            default:
                problem("invalid simProbe='" + raw + "' (use auto|all-ok|persistent-fail|plain-fail|all-fail)");
                return PROBE_AUTO;
        }
    }

    /** true = 强制"持久映射可用"，false = 强制不可用，null = 用真实探针。 */
    public static Boolean forcedPersistentProbe() {
        switch (simProbe()) {
            case PROBE_ALL_OK:
            case PROBE_PLAIN_FAIL:
                return Boolean.TRUE;
            case PROBE_ALL_FAIL:
            case PROBE_PERSISTENT_FAIL:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** true = 强制"普通映射可用"，false = 强制不可用，null = 用真实探针。 */
    public static Boolean forcedPlainProbe() {
        switch (simProbe()) {
            case PROBE_ALL_OK:
            case PROBE_PERSISTENT_FAIL:
                return Boolean.TRUE;
            case PROBE_ALL_FAIL:
            case PROBE_PLAIN_FAIL:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    public static String simRenderer() {
        String value = value("simRenderer");
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.equalsIgnoreCase("none") || text.equalsIgnoreCase("real") || text.equalsIgnoreCase("off")) {
            return null;
        }
        if (text.length() > 1) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if (first == last && (first == 34 || first == 39)) { // 34=双引号 39=单引号
                text = text.substring(1, text.length() - 1).trim();
            }
        }
        return text.isEmpty() ? null : text;
    }

    public static String mapMode() {
        String value = value("mapMode");
        if (value == null) {
            return null;
        }
        String mode = value.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("auto") || mode.equals("client") || mode.equals("tier1") || mode.equals("native")) {
            return mode;
        }
        problem("invalid mapMode='" + value + "' (use auto|tier1|client|native)");
        return null;
    }

    public static String forceDrawMethod() {
        String value = value("forceDrawMethod");
        if (value == null) {
            return null;
        }
        String method = value.trim().toLowerCase(Locale.ROOT);
        if (method.equals("indirect") || method.equals("basevertex") || method.equals("default")) {
            return method;
        }
        problem("invalid forceDrawMethod='" + value + "' (use indirect|basevertex|default)");
        return null;
    }

    public static String drawMethod() {
        String value = value("drawMethod");
        if (value == null) {
            return null;
        }
        String method = value.trim().toUpperCase(Locale.ROOT);
        if (method.equals("AUTO") || method.equals("BASEVERTEX") || method.equals("INDIRECT") || method.equals("DEFAULT")) {
            return method;
        }
        problem("invalid drawMethod='" + value + "' (use AUTO|BASEVERTEX|INDIRECT|DEFAULT)");
        return null;
    }

    public static Boolean mobileAutoEnable() {
        return triBool("mobileAutoEnable");
    }

    public static Boolean clientBufferFallback() {
        return triBool("clientBufferFallback");
    }

    public static Boolean safeDrawMethodOnClientBuffer() {
        return triBool("safeDrawMethodOnClientBuffer");
    }

    /** AR 的 SSBO/原子计数着色器重写开关（原 -Darforcemobile.enableSSBOFix 属性）。 */
    public static Boolean enableSsboFix() {
        return triBool("enableSSBOFix");
    }

    /** bench 窗口长度的默认值（秒）。 */
    public static final int BENCH_INTERVAL_DEFAULT_SECONDS = 10;

    /**
     * 帧时基准开关（1.3.23）：-Darforcemobile.bench=true 才真正启用。
     * 三态：未设置/非法返回 null（调用方按「关」处理，行为与 1.3.22 完全一致）。
     */
    public static Boolean bench() {
        return triBool("bench");
    }

    /** benchInterval 的原始提供值（仅用于摘要显示）；未设置返回 null。 */
    public static String benchIntervalRaw() {
        return value("benchInterval");
    }

    /**
     * 探针存储分支覆盖（1.3.25，诊断/模拟）：auto（默认）= 镜像运行期真实存储分支；
     * mutable = 强制走可变存储（glNamedBufferData/STREAM_DRAW）；immutable（别名 native）
     * = 强制走不可变存储（glNamedBufferStorage + 0xC2）。**只影响探针，不影响运行期**。
     * 非法值记入 problems 并回落 auto（返回 null）。
     */
    public static String probeStorage() {
        String raw = value("probeStorage");
        if (raw == null) {
            return null;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("auto")) {
            return "auto";
        }
        if (mode.equals("mutable")) {
            return "mutable";
        }
        if (mode.equals("immutable") || mode.equals("native")) {
            return "immutable";
        }
        problem("invalid probeStorage='" + raw + "' (use auto|mutable|immutable)");
        return null;
    }

    /** probeStorage 的原始提供值（仅用于摘要显示）；未设置返回 null。 */
    public static String probeStorageRaw() {
        return value("probeStorage");
    }

    /** profile 窗口长度的默认值（秒）。 */
    public static final int PROFILE_INTERVAL_DEFAULT_SECONDS = 10;

    /** profileStride 的默认值（每 N 帧统计一次）。 */
    public static final int PROFILE_STRIDE_DEFAULT = 1;

    /** 帧阶段归因器开关（1.3.26 W3，默认关）：三态，未设置/非法返回 null。 */
    public static Boolean profile() {
        return triBool("profile");
    }

    /** profileInterval（秒）：默认 10，非法值忽略并记入 problems。 */
    public static int profileIntervalSeconds() {
        return boundedInt("profileInterval", PROFILE_INTERVAL_DEFAULT_SECONDS, 1, 3600);
    }

    /** profileStride（每 N 帧一次）：默认 1，非法值忽略并记入 problems。 */
    public static int profileStride() {
        return boundedInt("profileStride", PROFILE_STRIDE_DEFAULT, 1, 64);
    }

    /** profileInterval / profileStride 的原始提供值（仅用于摘要显示）；未设置返回 null。 */
    public static String profileIntervalRaw() {
        return value("profileInterval");
    }

    public static String profileStrideRaw() {
        return value("profileStride");
    }

    /** 有界整数解析：非法值（非整数/越界）记入 problems 并回落默认值。 */
    private static int boundedInt(String key, int fallback, int min, int max) {
        String raw = value(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
            problem("invalid " + key + "='" + raw + "' (use " + min + ".." + max + ")");
        } catch (NumberFormatException e) {
            problem("invalid " + key + "='" + raw + "' (use an integer " + min + ".." + max + ")");
        }
        return fallback;
    }

    /**
     * 旧探针口径回退开关（1.3.25，默认 false = 新口径）：
     * true 时 plainMapWorks() 走 1.3.24 的"固定不可变 0xC2 存储 + 单次 WRITE 映射"口径。
     * 三态：未设置/非法返回 null（= 新口径）。
     */
    public static Boolean probeV1() {
        return triBool("probeV1");
    }

    /**
     * benchInterval 解析（秒）：默认 {@link #BENCH_INTERVAL_DEFAULT_SECONDS}（10 秒）。
     * 非法值（非整数 / <1 / >3600）被忽略并记入 {@link #problems()}，返回默认值。
     */
    public static int benchIntervalSeconds() {
        String raw = value("benchInterval");
        if (raw == null) {
            return BENCH_INTERVAL_DEFAULT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(raw.trim());
            if (seconds >= 1 && seconds <= 3600) {
                return seconds;
            }
            problem("invalid benchInterval='" + raw + "' (use 1..3600 seconds)");
        } catch (NumberFormatException e) {
            problem("invalid benchInterval='" + raw + "' (use an integer number of seconds)");
        }
        return BENCH_INTERVAL_DEFAULT_SECONDS;
    }

    /** 一行摘要；没有任何模拟参数且无非法值时返回 null。 */
    public static String summary() {
        String name = preset();
        if (LIST_PRESET.equals(name)) {
            return "available simulate presets: " + presetNames();
        }
        List<String> parts = new ArrayList<>();
        describe(parts, "simulate", name);
        String probe = simProbe();
        describe(parts, "simProbe", PROBE_AUTO.equals(probe) ? null : probe);
        describe(parts, "simRenderer", simRenderer());
        describe(parts, "mapMode", mapMode());
        describe(parts, "drawMethod", drawMethod());
        describe(parts, "forceDrawMethod", forceDrawMethod());
        describe(parts, "mobileAutoEnable", boolText(mobileAutoEnable()));
        describe(parts, "clientBufferFallback", boolText(clientBufferFallback()));
        describe(parts, "safeDrawMethodOnClientBuffer", boolText(safeDrawMethodOnClientBuffer()));
        describe(parts, "enableSSBOFix", boolText(enableSsboFix()));
        describe(parts, "bench", boolText(bench()));
        describe(parts, "benchInterval", benchIntervalRaw());
        describe(parts, "probeStorage", probeStorageSummary());
        describe(parts, "probeV1", boolText(probeV1()));
        describe(parts, "profile", boolText(profile()));
        describe(parts, "profileInterval", profileIntervalRaw());
        describe(parts, "profileStride", profileStrideRaw());
        String unknown = unknownReport();
        if (parts.isEmpty() && PROBLEMS.isEmpty() && unknown == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(String.join(" | ", parts));
        if (!PROBLEMS.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append("IGNORED: ").append(String.join("; ", PROBLEMS));
        }
        if (unknown != null) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append("UNKNOWN PROPERTY: ").append(unknown);
        }
        return builder.toString();
    }

    /**
     * probeStorage 的摘要文本（1.3.26 小修）：别名/大小写差异时显示生效值，
     * 例如 `probeStorage=native(effective=immutable)`；非法值只显示原文（problems 里已有说明）。
     */
    private static String probeStorageSummary() {
        String raw = probeStorageRaw();
        if (raw == null) {
            return null;
        }
        String effective = probeStorage();
        if (effective == null) {
            return raw;
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase(effective)) {
            return effective;
        }
        return trimmed + "(effective=" + effective + ")";
    }

    private static void describe(List<String> parts, String key, String value) {
        if (value == null) {
            return;
        }
        String source = source(key);
        parts.add(key + "=" + value + (source == null ? "" : "(" + source + ")"));
    }

    private static String boolText(Boolean value) {
        return value == null ? null : value.toString();
    }
}
