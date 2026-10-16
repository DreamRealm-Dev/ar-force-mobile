package com.arforcemobile;

import com.arforcemobile.mixin.BaseVertexContextAccessor;
import com.arforcemobile.mixin.SimpleLayerContextsAccessor;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.basevertex.BaseVertexDrawContextPool;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.pools.IDrawContextPool.IDrawContext;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.storage.ILayerContexts;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.storage.SimpleLayerContexts;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * BaseVertex 合批器(v1.2.1，仅手机环境启用)。
 *
 * 目标:AR 在 BASEVERTEX 模式下,同一 RenderType 的每个 section 都是一次
 * `elementBuffer.bind(count) + glDrawElementsBaseVertex(...)`。因为所有 context 共享
 * RenderSystem 的顺序索引缓冲(索引恒从 0 开始,只有 count/baseVertex 不同),
 * 所以连续同 RenderType 的 context 可以合并成一次
 * `glMultiDrawElementsBaseVertex(mode, counts[], type, indices=全0, baseVertices[])`,
 * 同时跳过组内成员的重复 setupRenderState/apply/clear。
 *
 * 只接管 SEPARATED 存储;不透明层按 RenderType 归拢(顺序无关,安全),半透明保序。
 * 任何异常 → 整帧回退原单条绘制,保证不劣于原行为。
 * 开关:-Darforcemobile.batchBaseVertex=true / -Darforcemobile.regroupOpaque=true
 */
public final class ARBaseVertexBatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    // TEMP-1.2.3-flickerTest: 合批临时禁用（默认 false），便于隔离闪烁是否来自合批。
    // 原默认值 true；如需恢复合批，启动参数加 -Darforcemobile.batchBaseVertex=true 即可，无需改代码。
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("arforcemobile.batchBaseVertex", "false"));
    public static final boolean REGROUP_OPAQUE = Boolean.parseBoolean(System.getProperty("arforcemobile.regroupOpaque", "true"));

    /** 出过异常后整帧禁用。 */
    private static boolean broken;
    private static long failCount;

    /** 观察到合并不上(全部单条)后永久禁用,消除空转开销。
     *  判定:累计 64 次提交且从未出现 length>=2 的组。 */
    private static boolean permanentlyDisabled;
    private static final long NO_MERGE_DISABLE_AFTER = 64L;

    /** ILayerContexts -> 本帧绘制视图(不透明层按 RenderType 归拢后的副本)。帧末清空。 */
    private static final Map<Object, List<Object>> views = new IdentityHashMap<>();

    // ---- 待提交组 ----
    private static RenderType pendingRt;
    private static Integer pendingGlMode;
    private static ShaderInstance pendingShader;
    private static final List<Object> pendingCtxs = new ArrayList<>();

    /** 当前列表是否由我们接管(iterator 重定向返回了视图)。 */
    private static boolean viewActive;

    // ---- 统计 ----
    private static long statSingle;
    private static long statMulti;
    private static long statMerged;
    private static long statFlushes;
    private static long statRunMax;
    private static int statGlErrors;

    // ---- 一次性诊断 ----
    private static boolean diagnosticsLogged;

    private ARBaseVertexBatch() {
    }

    public static boolean isEnabled() {
        // MGL 使用 INDIRECT，不接管 BASEVERTEX 合批。
        // 强制 INDIRECT 时同样不接管，避免 orderedView 空转。
        return ENABLED && ARForceEnvironment.isMobile() && !ARForceEnvironment.isMGL() &&
               !ARForceEnvironment.isForcedDrawMethodIndirect() &&
               !broken && !permanentlyDisabled;
    }

    /** 第一次 drawBuffers 时打印 AR 实际解析出的绘制方法(应恒为 BASEVERTEX,因为我们强制了)。 */
    public static void logDiagnosticsOnce() {
        if (diagnosticsLogged) {
            return;
        }
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.DEBUG_DIAGNOSTICS.get()) {
            return;
        }
        diagnosticsLogged = true;
        try {
            com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.IDrawMethod m =
                    com.github.argon4w.acceleratedrendering.core.CoreFeature.getDrawMethod();
            String name = String.valueOf(m);
            if (m == com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.basevertex.BaseVertexDrawMethod.INSTANCE) {
                name = "BASEVERTEX";
            } else if (m == com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.indirect.IndirectDrawMethod.INSTANCE) {
                name = "INDIRECT";
            }
            LOGGER.info("[ARForceMobile] AR draw method = {} ; BaseVertex batch engages = {}",
                    name, ENABLED && m == com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.basevertex.BaseVertexDrawMethod.INSTANCE);
        } catch (Throwable t) {
            LOGGER.warn("[ARForceMobile] Could not resolve AR draw method for diagnostics", t);
        }
    }

    private static void fail(Throwable t) {
        broken = true;
        if (failCount < 5) {
            LOGGER.error("[ARForceMobile] BaseVertex batch disabled for this frame after failure", t);
        } else if (failCount == 5) {
            LOGGER.error("[ARForceMobile] BaseVertex batch failing repeatedly; further failures suppressed (count={})", failCount);
        }
        failCount++;
    }

    // ============ 帧生命周期 ============

    /** clearBuffers 时调用。 */
    public static void resetFrame() {
        views.clear();
        resetPending();
        viewActive = false;
        broken = false;
    }

    /** drawBuffers HEAD 时调用(不动 viewActive)。 */
    public static void resetPending() {
        pendingRt = null;
        pendingGlMode = null;
        pendingShader = null;
        pendingCtxs.clear();
    }

    // ============ 视图 ============

    /** 绘制循环拿 iterator 前调用:不透明列表按 RenderType 归拢(副本),半透明保序。 */
    @SuppressWarnings("unchecked")
    public static List<IDrawContext> orderedView(ILayerContexts list) {
        if (!isEnabled()) {
            viewActive = false;
            return null;
        }
        try {
            if (!(list instanceof SimpleLayerContexts)) {
                viewActive = false;
                return null;
            }
            List<Object> v = views.get(list);
            if (v == null) {
                List<IDrawContext> src = ((SimpleLayerContextsAccessor) list).ar$contexts();
                if (src == null || src.isEmpty()) {
                    viewActive = false;
                    return null;
                }
                v = new ArrayList<>(src);
                RenderType rt = src.get(0).getRenderType();
                boolean opaque = rt == null || !rt.sortOnUpload;
                if (opaque && REGROUP_OPAQUE) {
                    v.sort(Comparator.comparingInt(c -> System.identityHashCode(((IDrawContext) c).getRenderType())));
                }
                views.put(list, v);
            }
            viewActive = true;
            return (List<IDrawContext>) (List<?>) v;
        } catch (Throwable t) {
            fail(t);
            viewActive = false;
            return null;
        }
    }

    // ============ 绘制阶段 ============

    /** setupRenderState 前调用:RenderType 变化时先提交上一组。 */
    public static void onSetupRenderState(RenderType rt) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (pendingRt != null && pendingRt != rt) {
                flushGroup();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** clear(shader) 前调用:组内成员跳过并捕获 shader。返回 true=跳过原 clear。 */
    public static boolean onClear(ShaderInstance shader) {
        if (!isEnabled()) {
            return false;
        }
        try {
            if (pendingRt != null) {
                pendingShader = shader;
                return true;
            }
            return false;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** clearRenderState(rt) 前调用。返回 true=跳过。 */
    public static boolean onClearRenderState(RenderType rt) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return pendingRt != null;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /**
     * drawElements(ctx, mode) 前调用。
     * @return true=已延迟到合批组;false=调用方走原逻辑。
     */
    public static boolean onDrawElements(IDrawContext ctx, VertexFormat.Mode mode) {
        if (!isEnabled() || !viewActive || !(ctx instanceof BaseVertexDrawContextPool.DrawContext)) {
            return false;
        }
        try {
            RenderType rt = ctx.getRenderType();
            if (pendingRt == null || pendingRt != rt) {
                if (pendingRt != null) {
                    flushGroup();
                }
                pendingRt = rt;
                pendingGlMode = mode == null ? 0 : mode.asGLMode;
            }
            pendingCtxs.add(ctx);
            statMerged++;
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** bind/unbind/runBefore/runAfter 前调用:提交残留组。 */
    public static void flushGroup() {
        if (!isEnabled() || pendingCtxs.isEmpty() || pendingRt == null) {
            return;
        }
        try {
            int n = pendingCtxs.size();
            int glMode = pendingGlMode == null ? 0 : pendingGlMode;

            BaseVertexContextAccessor first = (BaseVertexContextAccessor) pendingCtxs.get(0);
            RenderSystem.AutoStorageIndexBuffer buf = first.ar$elementBuffer();

            int maxCount = 0;
            int[] counts = new int[n];
            int[] baseVertices = new int[n];
            for (int i = 0; i < n; i++) {
                BaseVertexContextAccessor acc = (BaseVertexContextAccessor) pendingCtxs.get(i);
                counts[i] = acc.ar$count();
                baseVertices[i] = acc.ar$baseVertex();
                if (counts[i] > maxCount) {
                    maxCount = counts[i];
                }
            }

            // 一次性绑定共享顺序索引缓冲(原逻辑每 context 绑一次)
            buf.bind(maxCount);
            int type = buf.type().asGLType;

            if (n == 1) {
                GL32.glDrawElementsBaseVertex(glMode, counts[0], type, 0L, baseVertices[0]);
                statSingle++;
            } else {
                IntBuffer countsBuf = MemoryUtil.memAllocInt(n);
                IntBuffer baseBuf = MemoryUtil.memAllocInt(n);
                PointerBuffer idxBuf = MemoryUtil.memAllocPointer(n);
                try {
                    for (int i = 0; i < n; i++) {
                        countsBuf.put(i, counts[i]);
                        baseBuf.put(i, baseVertices[i]);
                        idxBuf.put(i, 0L); // 所有成员索引都从共享顺序缓冲的 0 开始
                    }
                    GL32.glMultiDrawElementsBaseVertex(glMode, countsBuf, type, idxBuf, baseBuf);
                    statMulti++;
                    if (n > statRunMax) {
                        statRunMax = n;
                    }
                } finally {
                    MemoryUtil.memFree(countsBuf);
                    MemoryUtil.memFree(baseBuf);
                    MemoryUtil.memFree(idxBuf);
                }
            }
            statFlushes++;
            if (ARForceConfig.DEBUG_DIAGNOSTICS.get()) {
                checkGlError();
            }
            if (pendingShader != null) {
                pendingShader.clear();
            }
            pendingRt.clearRenderState();

            // 自适应:跑了一阵子还从没合出过 length>=2 的组 → 说明此负载无合批空间,
            // 永久禁用,避免 defer/flush 空转开销(裸 BASEVERTEX 即可)。
            if (!permanentlyDisabled && statFlushes >= NO_MERGE_DISABLE_AFTER && statRunMax <= 1) {
                permanentlyDisabled = true;
                LOGGER.info("[ARForceMobile] BaseVertex batch: {} flushes, never merged (maxRun={}); disabling to avoid overhead",
                        statFlushes, statRunMax);
            }
        } catch (Throwable t) {
            fail(t);
        } finally {
            resetPending();
        }

        if (ARForceConfig.DEBUG_DIAGNOSTICS.get() && (statFlushes & 0x1FF) == 0) {
            LOGGER.info("[ARForceMobile] BaseVertexBatch stats: multi={} single={} commands={} maxRun={} glErrors={}",
                    statMulti, statSingle, statMerged, statRunMax, statGlErrors);
        }
    }

    /** flush 后查一次 GL 错误(诊断)。默认关闭，避免每组合批都增加一次 GL 查询开销。 */
    public static void checkGlError() {
        if (!ARForceConfig.DEBUG_DIAGNOSTICS.get()) {
            return;
        }
        int err = GL32.glGetError();
        if (err != 0) {
            statGlErrors++;
            if (statGlErrors <= 10) {
                LOGGER.warn("[ARForceMobile] GL error after BaseVertexBatch flush: 0x{}", Integer.toHexString(err));
            }
        }
    }
}
