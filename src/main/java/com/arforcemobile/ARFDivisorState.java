package com.arforcemobile;

import dev.engine_room.flywheel.backend.gl.GlNumericType;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.array.VertexAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * S5：Flywheel instance 数据 TBO 采样 → GLES 原生 instanced vertex attribute（divisor）。
 *
 * 背景（R6，latest-51/debug-15）：上传层全线结案——uploads=5×beginFrames 恒等于
 * Frame/Player/Level/Fog/Options 五个 UBO 的每帧真数据，instance 数据零变化（纯刚体），
 * TBO 重绑已被去重杀光。instancing 与 off 的差距只剩结构性嫌疑：MG 2.0 的 TBO 是
 * 模拟层（R3 实证"重绑全量重拷"），vertex shader 逐顶点 texelFetch 走翻译路径；
 * 而 off 路径顶点数据全在普通 VBO attribute 快路径。divisor attribute 是 GLES 3.0
 * 核心特性（GLES 3.1 起另有 vertex attrib binding），MG 原生支持。
 *
 * 结构（javap 实证，Flywheel 1.0.6）：
 * - shader 侧：InstanceAssemblerComponent 生成 _flw_unpackInstance(int)，
 *   函数体为 _flw_instances(usamplerBuffer) 的 texelFetch 序列，每个 uvec4 对应
 *   instance 数据的 16 字节。本类在 GlCompat.safeShaderSource 边界做文本替换：
 *   按 fetch 出现顺序替换为 _flw_iN attribute 引用，并在顶部声明
 *   layout(location=6+N) in uvec4 _flw_iN。mesh 的 6 个 attribute 同步钉在 0..5。
 * - 绑定侧：InstancedDraw.render/renderOne 里的 instancer.bind(tbo)（原 TBO 重绑
 *   choke point）改为 vao.bindVertexBuffer(1, vbo, 0, stride) ＋
 *   bindAttributes(1, 6, N×UINT4) ＋ setBindingDivisor(1, 1)。
 *   relativeOffset 按 attribute 顺序累加 = i×16，与 fetch 序号天然对齐。
 *
 * fail-open 纪律（§8.4）：patch 失败/绑定异常 → 全局 deactivate（本会话内），
 * 日志一次；用户游戏内 /arforce s5 off 可随时关闭。deactivate 后已编译的 patched
 * shader 仍挂着 TBO 无效绑定，重切一次 Flywheel backend 即恢复（重编译读新 flag）。
 *
 * 运行时开关只改内存，重启回 toml 默认（flywheel_tbo_divisor）——保证每次开测
 * 初始状态确定。
 */
public final class ARFDivisorState {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** mesh 顶点属性占 location 0..5（vertex_input.vert 顺序＝InternalVertex.ATTRIBUTES 顺序）。 */
    public static final int MESH_ATTRIB_COUNT = 6;
    /** instance attribute 从 location 6 起。 */
    public static final int INSTANCE_ATTRIB_BASE = MESH_ATTRIB_COUNT;
    /** Flywheel GlVertexArray 里 mesh VBO 用 binding index 0（MeshPool.bind 实证），instance 用 1。 */
    public static final int INSTANCE_BINDING_INDEX = 1;

    private static volatile Boolean runtimeOverride;
    private static volatile Boolean uboRuntimeOverride;
    private static volatile boolean disabledThisSession;
    private static volatile boolean loggedApplied;
    private static volatile boolean patchApplied;
    private static volatile boolean pendingRebuild;
    private static volatile int patchMaxAttrs;

    private static GlVertexArray currentVao;
    private static long lastBindingKey = -1L;
    private static String lastDisableReason;

    private ARFDivisorState() {
    }

    /** 运行时总闸：toml 默认 ＋ 命令覆盖 ＋ 会话降级。 */
    public static boolean effectiveEnabled() {
        if (disabledThisSession) {
            return false;
        }
        Boolean override = runtimeOverride;
        if (override != null) {
            return override;
        }
        try {
            return ARForceConfig.FLYWHEEL_TBO_DIVISOR.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** /arforce s5 on：重新武装（撤销本会话降级）。 */
    public static void setRuntimeEnabled(boolean enabled) {
        runtimeOverride = enabled;
        if (enabled) {
            disabledThisSession = false;
            lastDisableReason = null;
        }
        forcePipelineRebuild();
    }

    /**
     * R8 教训（K24）：backend 重切**不触发** shader 重编译——ShaderCache key=name，
     * 全会话只在首次 submitDraws 编译一次。S5 off 后重切会命中 S5 on 时缓存的
     * patched shader，而绑定侧按 effectiveEnabled()=false 走 TBO——
     * "attribute shader＋TBO 绑定"＝instance 数据全 0＝contraption 塌缩渲染异常。
     * 修法：/arforce 切换时主动 deleteAll 清掉 shader 缓存，重切后按新 flag 重编译。
     * deleteAll 是 Flywheel 自己命令在用的正常清理路径（非 R7 那种异常 kill），
     * 但仍保留"重切一次 backend"的指引以确保完整 reload 链。
     */
    private static void forcePipelineRebuild() {
        try {
            dev.engine_room.flywheel.backend.compile.PipelineCompiler.deleteAll();
        } catch (Throwable t) {
            LOGGER.warn("[ARForceMobile] S5: failed to clear pipeline cache ({}), re-switching the backend twice may be needed", t.toString());
        }
    }

    /** 绑定侧联动闸：至少一次 shader patch 成功后才允许 divisor 绑定——
     * 防"patch mixin 静默失效（K 风格 defaultRequire:0）但绑定 mixin 活着"的
     * TBO shader＋divisor 绑定塌缩组合。 */
    public static boolean isPatchApplied() {
        return patchApplied;
    }

    /**
     * 编译结果感知（DivisorShaderPatchMixin TAIL）：patched 的 instancing pipeline
     * shader 若编译失败，立即本会话降级——R7 教训：lazy 编译炸掉后
     * InstancingPrograms.instance 被 kill（allLoaded() 只查 instance!=null），
     * instancing 后端整机不可用，用户会被永久困在 off 且无指引。
     * 降级后重切一次 backend 即按新 flag 重编译回 TBO 管线（可恢复）。
     */
    public static void onCompileResult(dev.engine_room.flywheel.backend.compile.core.ShaderResult result, String name) {
        if (!patchApplied || result == null || name == null || !name.contains("instancing")) {
            return;
        }
        try {
            result.unwrap();
        } catch (Throwable t) {
            deactivate("patched shader failed to compile: " + name + " (" + t.getMessage() + ")", true);
        }
    }

    /** UBO 去重的运行时覆盖（null=跟随 toml）。 */
    public static Boolean uboRuntimeOverride() {
        return uboRuntimeOverride;
    }

    public static void setUboRuntimeOverride(Boolean value) {
        uboRuntimeOverride = value;
    }

    public static boolean uboEffectiveEnabled() {
        Boolean override = uboRuntimeOverride;
        if (override != null) {
            return override;
        }
        try {
            return ARForceConfig.FLYWHEEL_UBO_DEDUP.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 全局降级（本会话）。绑定异常时调用；重切 backend 后按新 flag 重编译。 */
    public static void deactivate(String reason) {
        deactivate(reason, false);
    }

    /**
     * @param deferRebuild true=在编译流程内触发（onCompileResult），deleteAll 延迟到
     *                     render TAIL 帧间安全点执行，避免打断编译批次。
     */
    public static void deactivate(String reason, boolean deferRebuild) {
        if (disabledThisSession) {
            return;
        }
        disabledThisSession = true;
        lastDisableReason = reason;
        ARFSubmitTracker.onS5Fallback();
        if (deferRebuild) {
            pendingRebuild = true;
        } else {
            forcePipelineRebuild();
        }
        LOGGER.warn("[ARForceMobile] S5 divisor DISABLED this session ({}) - re-switch the Flywheel backend once to restore the TBO pipeline", reason);
    }

    /** InstancedDrawManager.render TAIL 消费：帧间执行缓存清理（K24）。 */
    public static boolean consumePendingRebuild() {
        if (pendingRebuild) {
            pendingRebuild = false;
            forcePipelineRebuild();
            return true;
        }
        return false;
    }

    /** /arforce status 用的一行状态。 */
    public static String statusLine() {
        String mode = runtimeOverride == null ? "toml" : (runtimeOverride ? "cmd:on" : "cmd:off");
        String state = disabledThisSession
                ? "FALLBACK(" + lastDisableReason + ")"
                : (effectiveEnabled() ? "ACTIVE" : "off");
        String uboMode = uboRuntimeOverride == null ? "toml" : (uboRuntimeOverride ? "cmd:on" : "cmd:off");
        return "divisor=" + state + " (src=" + mode + ", maxAttrs=" + patchMaxAttrs + ") uboDedup="
                + (uboEffectiveEnabled() ? "on" : "off") + " (src=" + uboMode + ")";
    }

    /**
     * shader 源 patch（GlCompat.safeShaderSource 边界，编译期）。
     * 仅处理含 _flw_unpackInstance ＋ texelFetch ＋ _flw_instances 的顶点源（instance assembler）；
     * indirect 路径的 SSBO assembler 无 texelFetch，天然不匹配。
     */
    public static String patchShaderSource(String src) {
        if (src == null) {
            return src;
        }
        if (!effectiveEnabled()) {
            // 关闭态重编译：清 patch 标志，强制绑定侧与 TBO shader 保持一致
            patchApplied = false;
            return src;
        }
        if (!src.contains("_flw_unpackInstance") || !src.contains("texelFetch") || !src.contains("_flw_instances")) {
            return src;
        }
        try {
            StringBuilder sb = new StringBuilder(src);
            int fetches = replaceInstanceFetches(sb);
            if (fetches <= 0) {
                return src;
            }
            if (!pinMeshAttributeLocations(sb)) {
                deactivate("mesh attribute declarations not found");
                return src;
            }
            removeSamplerDeclaration(sb);

            StringBuilder decl = new StringBuilder();
            for (int i = 0; i < fetches; i++) {
                decl.append("layout(location = ").append(INSTANCE_ATTRIB_BASE + i).append(") in uvec4 _flw_i").append(i).append(";\n");
            }
            // K23：anchor 必须是 unpack 的"定义处"（带返回类型前缀），绝不能用首次出现——
            // main.vert 的调用点 `FlwInstance instance = _flw_unpackInstance(...)` 在 void main()
            // 函数体内，1.3.13 把全局 attribute 声明插进了函数体，Adreno 报 "Invalid #version"，
            // lazy 编译炸掉后 InstancingPrograms.instance 被 kill，instancing 后端整机不可用。
            // `FlwInstance _flw_unpackInstance(` 只匹配定义（调用处是 `FlwInstance instance = _flw_unpackInstance(`，
            // FlwInstance 后跟的是 ` instance` 不是 ` _flw`），天然排除。
            int def = sb.indexOf("FlwInstance _flw_unpackInstance(");
            if (def < 0) {
                deactivate("unpack function definition not found");
                return src;
            }
            int lineStart = sb.lastIndexOf("\n", def) + 1;
            sb.insert(lineStart, decl);

            if (!patchedSelfCheck(sb, fetches)) {
                deactivate("patched source failed self-check");
                return src;
            }

            if (fetches > patchMaxAttrs) {
                patchMaxAttrs = fetches;
            }
            patchApplied = true;
            if (!loggedApplied) {
                loggedApplied = true;
                LOGGER.info("[ARForceMobile] S5 divisor shader patched ({} vec4 instance attributes, base location {})", fetches, INSTANCE_ATTRIB_BASE);
                dumpAnchorContext(sb, fetches);
            }
            return sb.toString();
        } catch (Throwable t) {
            deactivate("shader patch failed: " + t);
            return src;
        }
    }

    /** patch 后硬自检：#version 仍是首行、声明数与替换数一致、花括号平衡——任一不过即放弃 patch。 */
    private static boolean patchedSelfCheck(StringBuilder sb, int fetches) {
        String patched = sb.toString();
        if (!patched.startsWith("#version")) {
            return false;
        }
        int braces = 0;
        for (int i = 0; i < patched.length(); i++) {
            char c = patched.charAt(i);
            if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
            }
        }
        if (braces != 0) {
            return false;
        }
        int decls = 0;
        int idx = 0;
        while ((idx = patched.indexOf("in uvec4 _flw_i", idx)) >= 0) {
            decls++;
            idx += 4;
        }
        return decls == fetches;
    }

    /** 首次 patch 打印声明块插入点前后各 6 行（限宽），供日志人工审查（R7 教训）。 */
    private static void dumpAnchorContext(StringBuilder sb, int fetches) {
        try {
            String src = sb.toString();
            int def = src.indexOf("FlwInstance _flw_unpackInstance(");
            if (def < 0) {
                return;
            }
            int lineStart = src.lastIndexOf('\n', def) + 1;
            int before = 0;
            for (int n = 0; n < 6 && lineStart > 0; n++) {
                int prev = src.lastIndexOf('\n', lineStart - 2);
                before = prev + 1;
                lineStart = before;
            }
            int end = src.indexOf("return instance;", def);
            if (end < 0) {
                end = Math.min(src.length(), def + 800);
            }
            String ctx = src.substring(Math.max(0, before), Math.min(src.length(), end));
            ctx = ctx.replace("\n", " ⏎ ");
            if (ctx.length() > 1000) {
                ctx = ctx.substring(0, 1000) + "...";
            }
            LOGGER.info("[ARForceMobile] S5 patch context ({} fetches): {}", fetches, ctx);
        } catch (Throwable ignored) {
        }
    }

    /** 把 _flw_instances 的每个 texelFetch 调用按出现顺序替换为 _flw_iN attribute 引用。 */
    private static int replaceInstanceFetches(StringBuilder sb) {
        int count = 0;
        int pos = 0;
        while (true) {
            int call = sb.indexOf("texelFetch", pos);
            if (call < 0) {
                break;
            }
            int open = call + "texelFetch".length();
            while (open < sb.length() && Character.isWhitespace(sb.charAt(open))) {
                open++;
            }
            if (open >= sb.length() || sb.charAt(open) != '(') {
                pos = call + "texelFetch".length();
                continue;
            }
            int close = matchingParen(sb, open);
            if (close < 0) {
                pos = open;
                continue;
            }
            if (sb.indexOf("_flw_instances", open) >= 0 && sb.indexOf("_flw_instances", open) < close) {
                sb.replace(call, close + 1, "_flw_i" + count);
                count++;
                pos = call;
            } else {
                pos = close + 1;
            }
        }
        return count;
    }

    private static int matchingParen(StringBuilder sb, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * mesh 6 attribute 显式钉 location 0..5（防 linker 分配与 6..N 冲突）。
     * 声明顺序＝vertex_input.vert＝InternalVertex.LAYOUT：Pos/Color/TexCoord/Overlay/Light/Normal。
     */
    private static boolean pinMeshAttributeLocations(StringBuilder sb) {
        String[][] attrs = {
                {"vec3", "_flw_aPos"},
                {"vec4", "_flw_aColor"},
                {"vec2", "_flw_aTexCoord"},
                {"vec2", "_flw_aOverlay"},
                {"vec2", "_flw_aLight"},
                {"vec3", "_flw_aNormal"},
        };
        for (int i = 0; i < attrs.length; i++) {
            String plain = "in " + attrs[i][0] + " " + attrs[i][1] + ";";
            int at = sb.indexOf(plain);
            if (at < 0) {
                return false;
            }
            sb.replace(at, at + plain.length(), "layout(location = " + i + ") " + plain);
        }
        return true;
    }

    /** 删掉 TBO sampler 声明（patched shader 不再采样；宽松匹配，失败无害）。 */
    private static void removeSamplerDeclaration(StringBuilder sb) {
        String decl = "uniform usamplerBuffer _flw_instances";
        int at = sb.indexOf(decl);
        if (at < 0) {
            return;
        }
        int end = sb.indexOf(";", at);
        if (end >= 0) {
            sb.delete(at, end + 1);
        }
    }

    /** InstancedDrawManager.render HEAD 传入当前 VAO；换 VAO（backend 重建）时重置绑定缓存。 */
    public static void setCurrentVao(GlVertexArray vao) {
        if (vao != currentVao) {
            currentVao = vao;
            lastBindingKey = -1L;
        }
    }

    /** InstancedDraw redirect 侧读取（render HEAD 之后非空）。 */
    public static GlVertexArray currentVao() {
        return currentVao;
    }

    /**
     * 绑定侧每 draw 调用（VAO 已 bindForDraw）：binding 1 → instancer vbo，
     * attributes/divisor 按 (stride, attrCount) 缓存，稳态每帧每组只设一次。
     * attrCount = max(patch 声明数, stride/16)：多绑的 enabled attribute 无 shader
     * 消费时被驱动忽略，合法；少了会读常量 0，宁可多。
     */
    public static void bindInstanceBuffer(GlVertexArray vao, int vboHandle, int stride) {
        int attrCount = Math.max(patchMaxAttrs, stride / 16);
        long key = ((long) stride << 32) | attrCount;
        if (vao != currentVao) {
            currentVao = vao;
            lastBindingKey = -1L;
        }
        if (key != lastBindingKey) {
            List<VertexAttribute> attrs = new ArrayList<>(attrCount);
            for (int i = 0; i < attrCount; i++) {
                attrs.add(new VertexAttribute.Int(GlNumericType.UINT, 4));
            }
            vao.bindAttributes(INSTANCE_BINDING_INDEX, INSTANCE_ATTRIB_BASE, attrs);
            vao.setBindingDivisor(INSTANCE_BINDING_INDEX, 1);
            lastBindingKey = key;
        }
        vao.bindVertexBuffer(INSTANCE_BINDING_INDEX, vboHandle, 0L, stride);
    }
}
