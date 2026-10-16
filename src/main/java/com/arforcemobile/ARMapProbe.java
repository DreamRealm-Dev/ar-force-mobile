package com.arforcemobile;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 持久映射能力探测。
 *
 * 背景：AR 1.0.14 的 MappedBuffer 在构造时用 GL46 DSA 创建缓冲并以
 * WRITE|PERSISTENT|COHERENT (0xC2) 标志映射（glNamedBufferStorage +
 * glMapNamedBufferRange），映射返回的 CPU 指针存进 address 字段，之后所有顶点/变换
 * 写入都直接写这块内存，且没有任何空指针校验。
 *
 * MobileGlues 的 DSA 包装(glMapNamedBufferRange)最终把标志原样透传给 GLES 驱动的
 * glMapBufferRange；GLES 并不定义 PERSISTENT/COHERENT 位——一旦驱动拒绝（或
 * EXT_buffer_storage 缺失导致 glBufferStorage 静默 no-op、缓冲没有存储），映射返回
 * NULL，address=0，第一次实体渲染 beginTransform 就会 memPutFloat(0) 直接 SIGSEGV
 * (hs_err: si_addr=0x0, putMatrix4f)。
 *
 * 因此在强制打开 AR 之前，用与 AR 完全相同的调用序列做一次真实探测：
 * 探测通过才允许 force-enable；失败则保持 AR 关闭，避免崩溃。
 *
 * 1.3.25 口径修复（Spec: H-probe §2）：旧探针把「存储有没有被创建」测成了「映射能不能用」——
 * 它固定用不可变 0xC2 存储，而 MG 在宿主缺 GL_EXT_buffer_storage 时会静默 no-op
 * （glBufferStorage 不分配存储），于是探针返回 NULL、判定"不可用"，而运行期走可变存储
 * 本该可用。新口径按运行期真实分支建存储、走 map(0xC2) → unmap → remap(WRITE) 两步，
 * 判定只看 remap；旧口径降级为同一行的 [legacy=…] 交叉日志。
 *
 * 注意不要用 caps.OpenGL46 之类的版本标志做前置门槛：MobileGlues 自报
 * customGLVersion=4.0，版本标志为 false，但 DSA 函数指针真实存在（AR 正是靠它
 * 跑起来的），只能以真实调用结果为准。
 * 探测结果按会话缓存，但**只缓存确定性结论**（1.3.24 B3）：
 * - 成功（指针非 0）或驱动明确返回 NULL -> 缓存；
 * - GL 上下文未就绪 / 调用抛异常 / glCreateBuffers 返回 0 -> **不缓存**，等下次调用重试。
 * 旧实现在异常路径上直接缓存 false，若探针被非 GL 线程或早期加载路径首次触达，
 * 整个会话的 tier-1/tier-0 就被静默判死（H-probe B3）。
 */
public final class ARMapProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final int PROBE_SIZE = 112;
    private static final int MAP_FLAGS = GL46.GL_MAP_WRITE_BIT
            | GL46.GL_MAP_PERSISTENT_BIT
            | GL46.GL_MAP_COHERENT_BIT;

    /** null = 尚未得出确定性结论（可重试）；true/false = 已缓存结果。 */
    private static volatile Boolean persistentMapWorks;

    /** null = 尚未得出确定性结论（可重试）；true/false = 已缓存结果。 */
    private static volatile Boolean plainMapWorks;

    /** 新口径探针的日志（含旧口径交叉结论）每次会话只打一次（A5：每条路径最多一次）。 */
    private static boolean loggedProbe;

    private ARMapProbe() {
    }

    /**
     * 探测 tier-1 的"去位降级映射"是否可用：与 ImmutableBufferSafeMapMixin 的运行期
     * 重试完全同参——0xC2 存储标志创建，仅 GL_MAP_WRITE_BIT 映射。
     * 普通映射可用时 AR 走 tier-1（性能仅略低于原生、无双重拷贝）。
     */
    /**
     * 探测 tier-1 的"去位降级映射"是否可用（1.3.25 新口径，Spec: H-probe §2）。
     *
     * 与运行期**同参同序**：
     *   1) 存储分支镜像 ImmutableBufferStorageFlagsMixin.java:42-44 的真实判定
     *      （native = !isMobile || isNativeOnly || (persistentMapWorks && !forceClient)；
     *       non-native 且 clientUpload=orphan -> nglNamedBufferData(STREAM_DRAW) 可变存储，
     *       否则 glNamedBufferStorage(0xC2[+DYNAMIC]) 不可变存储）；
     *   2) 映射两步：map(0xC2) ->（成功则 unmap）-> remap(WRITE)；
     *   3) 判定只看 **remap(WRITE) 是否非 0**（首映结果只进日志）。
     *
     * 旧口径（固定不可变存储 + 单次 WRITE 映射）保留为同一行的 `[legacy=…]` 交叉日志，
     * **不参与判定**（只跑一次）；-Darforcemobile.probeV1=true 时改回旧口径作为判定（显式回退）。
     * 缓存纪律（B3）：只缓存确定性结论（成功 / 驱动明确返回 NULL）；异常与 GL 调用失败不缓存。
     * 诊断覆盖：-Darforcemobile.probeStorage=mutable|immutable 强制探针的存储分支（不改运行期）。
     */
    public static boolean plainMapWorks() {
        Boolean cached = plainMapWorks;
        if (cached != null) {
            return cached;
        }

        // 模拟/调试：-Darforcemobile.simProbe=... 强制探针结果（不设置时完全不介入）。
        Boolean forced = ARSimArgs.forcedPlainProbe();
        if (forced != null) {
            plainMapWorks = forced;
            LOGGER.info("[ARForceMobile] SimProbe: plain mapping forced {} ({}), real probe skipped",
                    forced ? "OK" : "FAIL", ARSimArgs.source("simProbe"));
            return forced;
        }

        if (safeCaps() == null) {
            return false; // 上下文未就绪：不缓存，下次重试
        }

        // 显式回退：-Darforcemobile.probeV1=true -> 用 1.3.24 的旧口径判定。
        if (Boolean.TRUE.equals(ARSimArgs.probeV1())) {
            return probeLegacyMode();
        }

        return probeRuntimeMirror();
    }

    /** -Darforcemobile.probeV1=true：用 1.3.24 的旧口径作为判定（显式回退）。 */
    private static boolean probeLegacyMode() {
        String legacy = probeImmutableSingleMap();
        boolean ok = "OK".equals(legacy);
        if (!"err".equals(legacy)) {
            plainMapWorks = ok; // 只有拿到确定性结论才缓存
        }
        if (!loggedProbe) {
            loggedProbe = true;
            LOGGER.info("[ARForceMobile] Probe(tier1): v1 legacy mode (-Darforcemobile.probeV1=true) -> tier-1 {} [{}]",
                    ok ? "available" : "unavailable", storageExtensionReport());
        }
        return ok;
    }

    /** 新口径：镜像运行期的存储分支 + 两步映射，判定只看 remap(WRITE)。 */
    private static boolean probeRuntimeMirror() {
        // 1) 存储分支：ImmutableBufferStorageFlagsMixin.java:42-44 的取反（含 isMobile()）。
        boolean nativeTier = !ARForceEnvironment.isMobile()
                || ARMapMode.isNativeOnly()
                || (persistentMapWorks() && !ARMapMode.isForceClient());
        String override = ARSimArgs.probeStorage();
        boolean mutable;
        if ("mutable".equals(override)) {
            nativeTier = false;
            mutable = true;
        } else if ("immutable".equals(override)) {
            nativeTier = true;
            mutable = false;
        } else {
            mutable = !nativeTier && ARClientBufferUploader.ORPHAN_UPLOAD;
        }

        int buffer = 0;
        try {
            buffer = GL46.glCreateBuffers();
            if (buffer == 0) {
                return false; // 调用失败：不缓存
            }
            String branch;
            if (nativeTier) {
                GL46.glNamedBufferStorage(buffer, PROBE_SIZE, MAP_FLAGS);
                branch = "immutable/0xC2";
            } else if (mutable) {
                GL46.nglNamedBufferData(buffer, PROBE_SIZE, 0L, GL46.GL_STREAM_DRAW);
                branch = "mutable/data(stream)";
            } else {
                GL46.glNamedBufferStorage(buffer, PROBE_SIZE, MAP_FLAGS | GL46.GL_DYNAMIC_STORAGE_BIT);
                branch = "immutable/0xC2+dynamic";
            }

            long first = GL46.nglMapNamedBufferRange(buffer, 0L, PROBE_SIZE, MAP_FLAGS);
            if (first != 0L) {
                GL46.glUnmapNamedBuffer(buffer);
            } else {
                // 1.3.26 小修：首映失败会在 GL 错误状态里留下痕迹（MG 上常见 GL_INVALID_OPERATION），
                // 不清掉会污染紧随其后的 remap 判定与后续调用。
                drainGlErrors();
            }
            long second = GL46.nglMapNamedBufferRange(buffer, 0L, PROBE_SIZE, GL46.GL_MAP_WRITE_BIT);
            boolean ok = second != 0L;
            if (ok) {
                GL46.glUnmapNamedBuffer(buffer);
                // 1.3.26：成功路径也要清错误状态——610 的预期形态正是 ctorMap=FAIL + remapWRITE=OK，
                // 首映失败留下的错误会一直挂到后面（MG 的 latch 语义下尤其容易误导）。
                drainGlErrors();
            } else {
                drainGlErrors();
            }

            plainMapWorks = ok; // 明确结论才缓存
            if (!loggedProbe) {
                loggedProbe = true;
                // 旧口径交叉日志：必须在写缓存之后跑（避免递归），每次会话只跑一次。
                String legacy = probeImmutableSingleMap();
                LOGGER.info("[ARForceMobile] Probe(tier1): storage={} ctorMap={} remapWRITE={} -> tier-1 {} [legacy={}] [{}]",
                        branch, first != 0L ? "OK" : "FAIL", ok ? "OK" : "FAIL",
                        ok ? "available" : "unavailable", legacy, storageExtensionReport());
            }
            return ok;
        } catch (Throwable t) {
            // 与 B3 一致：调用失败/异常不缓存，下次重试。
            LOGGER.warn("[ARForceMobile] Map probe(tier1): call failed, will retry later", t);
            return false;
        } finally {
            if (buffer != 0) {
                try {
                    GL46.glDeleteBuffers(buffer);
                } catch (Throwable ignored) {
                    // 删除失败不影响判定
                }
            }
        }
    }

    /**
     * 旧口径交叉探测（= 1.3.24 的 plainMapWorks 逻辑）：固定不可变 0xC2 存储 + 单次 WRITE 映射。
     * 返回 "OK" / "FAIL" / "err"；只用于日志与 probeV1 回退，不写 plainMapWorks 缓存。
     */
    private static String probeImmutableSingleMap() {
        int buffer = 0;
        try {
            buffer = GL46.glCreateBuffers();
            if (buffer == 0) {
                return "err";
            }
            GL46.glNamedBufferStorage(buffer, PROBE_SIZE, MAP_FLAGS);
            long ptr = GL46.nglMapNamedBufferRange(buffer, 0L, PROBE_SIZE, GL46.GL_MAP_WRITE_BIT);
            if (ptr != 0L) {
                GL46.glUnmapNamedBuffer(buffer);
                return "OK";
            }
            drainGlErrors();
            return "FAIL";
        } catch (Throwable t) {
            return "err";
        } finally {
            if (buffer != 0) {
                try {
                    GL46.glDeleteBuffers(buffer);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 拿 GL caps 并确认上下文可用；不可用时返回 null（调用方不得写缓存）。 */
    private static GLCapabilities safeCaps() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps == null) {
                return null;
            }
            if (GL11.glGetString(GL11.GL_VERSION) == null) {
                return null;
            }
            return caps;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 清掉探测过程留下的 GL 错误状态。
     *
     * 1.3.26 修正（MG 源码理论验证 task-20）：MG 的 glGetError **永远返回 GL_NO_ERROR**
     * （MobileGlues getter.cpp:183-209 —— 每次调用消费一个后端错误 + 一个前端 latch），
     * 所以 `while (glGetError() != GL_NO_ERROR)` 保证只跑一轮：一次失败排了 2 个错误时会漏 1 个。
     * 这里改成**固定 4 次**有界调用（不用返回值当循环条件），与 MG 的实际语义对齐。
     */
    private static void drainGlErrors() {
        for (int i = 0; i < 4; i++) {
            GL46.glGetError();
        }
    }

    /**
     * 一行扩展报告（诊断）。
     *
     * 1.3.26 修正（MG 源码理论验证 task-20）：**判别字段是 `ARB_buffer_storage`** ——
     * MG 只把宿主能力转写成 ARB 广告（gles/loader.cpp:269-271），**从不广告
     * `GL_EXT_buffer_storage`**（:212-213 读宿主）。所以 610/8G1 上 EXT 恒为 no，
     * 拿它当判据会误判；EXT 保留为「宿主侧」参考，要查它请去 /sdcard/MG/latest.log。
     */
    private static String storageExtensionReport() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps == null) {
                return "ext: (no caps)";
            }
            return "ext: ARB_buffer_storage=" + (caps.GL_ARB_buffer_storage ? "yes" : "no")
                    + " DSA=" + (caps.OpenGL45 || caps.GL_ARB_direct_state_access ? "yes" : "no")
                    + " (host EXT_buffer_storage=" + (extensionAdvertised("GL_EXT_buffer_storage") ? "yes" : "no")
                    + "; MG never advertises EXT - check /sdcard/MG/latest.log)";
        } catch (Throwable t) {
            return "ext: (unavailable)";
        }
    }

    /** 在 GL_EXTENSIONS 里找扩展名；核心 profile 下 glGetString 可能返回 null，则退回索引式查询。 */
    private static boolean extensionAdvertised(String name) {
        try {
            String all = GL11.glGetString(GL11.GL_EXTENSIONS);
            if (all != null) {
                return all.contains(name);
            }
        } catch (Throwable ignored) {
            // 退回索引式查询
        }
        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                if (name.equals(GL30.glGetStringi(GL11.GL_EXTENSIONS, i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就按"没有"处理
        }
        return false;
    }

    /**
     * 探测 AR 的持久映射路径是否可用。
     * 返回 true 时 ar-force-mobile 才应强制打开 AR。
     */
    public static boolean persistentMapWorks() {
        Boolean cached = persistentMapWorks;
        if (cached != null) {
            return cached;
        }

        // 模拟/调试：-Darforcemobile.simProbe=... 强制探针结果（不设置时完全不介入）。
        Boolean forced = ARSimArgs.forcedPersistentProbe();
        if (forced != null) {
            persistentMapWorks = forced;
            LOGGER.info("[ARForceMobile] SimProbe: persistent mapping forced {} ({}), real probe skipped",
                    forced ? "OK" : "FAIL", ARSimArgs.source("simProbe"));
            return forced;
        }

        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (Throwable t) {
            return false;
        }
        if (caps == null) {
            // GL 上下文未就绪：不缓存，下次再探。
            return false;
        }

        try {
            int buffer = GL46.glCreateBuffers();
            if (buffer == 0) {
                // 1.3.24（B3）：同 plainMapWorks —— 不缓存，下次重试。
                LOGGER.warn("[ARForceMobile] Map probe: glCreateBuffers returned 0, will retry later");
                return false;
            }

            boolean ok;
            try {
                GL46.glNamedBufferStorage(buffer, PROBE_SIZE, MAP_FLAGS);
                long ptr = GL46.nglMapNamedBufferRange(buffer, 0L, PROBE_SIZE, MAP_FLAGS);
                ok = ptr != 0L;
                if (ok) {
                    GL46.glUnmapNamedBuffer(buffer);
                } else {
                    // 清掉 MG/驱动留下的错误标志，避免污染后续 GL 调用的错误状态。
                    while (GL46.glGetError() != GL46.GL_NO_ERROR) {
                        // drain
                    }
                }
            } finally {
                GL46.glDeleteBuffers(buffer);
            }

            persistentMapWorks = ok;
            if (ok) {
                LOGGER.info("[ARForceMobile] Map probe: persistent+coherent mapping works, safe to enable AR");
            } else {
                LOGGER.warn("[ARForceMobile] Map probe: glMapNamedBufferRange(0xC2) returned NULL "
                        + "(GLES 透传不认 PERSISTENT/COHERENT 或缺 EXT_buffer_storage) -> keep AR disabled to avoid SIGSEGV");
            }
            return ok;
        } catch (Throwable t) {
            // 1.3.24（B3）：调用失败/异常一律不缓存（同为"不确定"），只有明确拿到 NULL 才判假。
            // 函数指针缺失这类硬性失败仍会走到这里，但每次调用重试的代价可忽略。
            LOGGER.warn("[ARForceMobile] Map probe: persistent mapping probe call failed, will retry later", t);
            return false;
        }
    }
}
