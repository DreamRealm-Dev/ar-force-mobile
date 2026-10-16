package com.arforcemobile;

import com.github.argon4w.acceleratedrendering.core.backends.buffers.MutableBuffer;
import org.lwjgl.opengl.GL46;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * 客户端内存回退的上传器。
 *
 * 写法（对应"任何 GLES 驱动都能跑"的第 4 层兜底）：
 * AR 的 MappedBuffer 本来靠 PERSISTENT|COHERENT 映射直接写 GPU 缓冲内存；
 * 驱动完全不支持映射时，改为 nmemAlloc 一块客户端内存当写入目标（map() 钩子把
 * 返回值替换成客户端指针），在每帧 prepareBuffers 前 / drawBuffers 前把本帧数据
 * 拷进 GL 缓冲。不依赖任何映射/持久映射/EXT_buffer_storage 能力。
 *
 * 1.3.6 脏集合驱动（8Gen1 stats 实测定案：真凶不是上传量）——
 * AR 在 client 模式下会创建上万（实测 17,472）个 MappedBuffer（mesh/批次池），
 * 旧版 flushAll() 每帧 4-8 次全量线性扫描注册表（几十万次虚调用/帧），
 * register() 的 O(n) contains 雪上加霜；实测上传量仅 ~1MB/帧，调用方式三版同分
 * 也是因为共享这笔固定税。现在：
 * - reserve() TAIL 标脏，flushAll() 只处理脏队列（通常每帧个位数）；
 * - 注册表换成 IdentityHashMap 支撑的 Set，register/unregister O(1)。
 *
 * 上传策略（-Darforcemobile.clientUpload=orphan|subdata，默认 orphan）：
 * 每帧首传先 glNamedBufferData(全容量, NULL) 换新存储（显式 orphan，零停顿），
 * 再 subData 本帧 [0,end)；同帧迟到写入只增量传尾巴，拷贝量=精确载荷。
 */
public final class ARClientBufferUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** 进入客户端模式的 MappedBuffer（AR 自身长期持有这些对象，此处强引用无额外泄漏）。 */
    private static final Set<Object> REGISTRY =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** 有未上传数据的缓冲（reserve 钩子标脏入队，flush 后出队），替代全量扫描。 */
    private static final ArrayDeque<Object> DIRTY = new ArrayDeque<>();

    /** true = 规范 orphan（默认）；false = 1.2.8 纯增量 subData（A/B 对照）。 */
    public static final boolean ORPHAN_UPLOAD = !"subdata".equalsIgnoreCase(
            System.getProperty("arforcemobile.clientUpload", "orphan"));

    /**
     * tier-2（客户端内存 + 每帧 orphan 上传）当前是否生效的标志，供绘制方法安全闸
     * （ForceBaseVertexMixin + ARForceConfig.safe_draw_method_on_client_buffer）判断。
     *
     * 置位来源（任一命中即为"AR 实际跑在 tier-2"）：
     * 1) register()：某个 MappedBuffer 真正进入客户端内存模式（运行期实测，最可靠）；
     * 2) AvailabilityUtilsMixin：两条映射探测都失败且 client_buffer_fallback=true，
     *    门禁即将以 tier-2 放行 AR —— 覆盖"环境对象先于首个缓冲被构造"的时序，
     *    因为 CoreFeature.getDrawMethod 只在 VanillaBufferEnvironment 构造时被调用一次；
     * 3) ARMapMode.isForceClient()（-Darforcemobile.mapMode=client），见 clientModeActive()。
     * 会话级粘性：一旦 tier-2 生效就不回落（档位在会话内不会改变）。
     */
    private static volatile boolean clientModeActive;

    private static boolean loggedFlush;
    private static boolean loggedOrphanBroken;
    private static volatile boolean orphanBroken;
    private static boolean broke;

    private static long diagLastNanos;
    private static int diagUploads;
    private static long diagBytes;

    private ARClientBufferUploader() {
    }

    public static void register(Object buffer) {
        // 进入客户端内存模式 = tier-2 真实生效：给绘制方法安全闸（BASEVERTEX→INDIRECT）用。
        markClientModeActive();
        REGISTRY.add(buffer);
    }

    public static void unregister(Object buffer) {
        REGISTRY.remove(buffer);
    }

    /** 标记 tier-2 生效（MappedBuffer 进入客户端模式 / 门禁以 tier-2 放行 AR 时调用）。 */
    public static void markClientModeActive() {
        clientModeActive = true;
    }

    /**
     * AR 当前是否实际运行在 tier-2（客户端内存 + 每帧 orphan 上传）路径。
     * 用于 ForceBaseVertexMixin 的安全闸：tier-2 下 BASEVERTEX 的
     * glDrawElementsBaseVertex 在 Adreno + MobileGlues 2.x 上会 SIGSEGV。
     */
    public static boolean clientModeActive() {
        return clientModeActive || ARMapMode.isForceClient();
    }

    /** reserve() 写入前进时调用：标脏入队（调用方已保证 !dirty 才进来）。 */
    public static void markDirty(Object buffer) {
        if (!broke) {
            DIRTY.addLast(buffer);
        }
    }

    /** 在 AR 消费缓冲（compute/draw）之前调用：只上传有新数据的缓冲。 */
    public static void flushAll() {
        if (broke || DIRTY.isEmpty()) {
            return;
        }
        maybeLogDiagnostics();
        while (!DIRTY.isEmpty()) {
            flushOne(DIRTY.pollFirst());
        }
    }

    private static void flushOne(Object buffer) {
        if (!(buffer instanceof ARClientBufferView v) || !v.arf$isClientMode()) {
            return;
        }
        long end = v.arf$getPosition();
        long start = v.arf$getUploaded();
        if (end <= start) {
            return;
        }
        long size = v.arf$getClientSize();
        if (end > size) {
            end = size;
            if (start >= end) {
                return;
            }
        }
        try {
            int handle = ((MutableBuffer) buffer).getBufferHandle();
            if (handle == 0) {
                return;
            }
            if (ORPHAN_UPLOAD && !orphanBroken && start == 0) {
                // 每帧首传：整块重声明到全容量（NULL 数据 = 只换存储不拷贝），
                // 再上传本帧 [0,end)；存储容量从此稳定，同帧尾巴走增量 subData。
                GL46.nglNamedBufferData(handle, size, 0L, GL46.GL_STREAM_DRAW);
                GL46.nglNamedBufferSubData(handle, 0L, end, v.arf$getClientPtr());
                v.arf$setStorageEnsured(true);
                ensureOrphanWorks(v, handle);
            } else {
                // 同帧增量尾巴，或 subdata 模式 / orphan 自动回退。
                // MG 缺 EXT_buffer_storage 时 glBufferStorage 静默 no-op，缓冲没有存储，
                // subData 会 GL_INVALID_OPERATION——首次上传前补一份可变存储。
                if (!v.arf$isStorageEnsured()) {
                    int glSize = GL46.glGetNamedBufferParameteri(handle, GL46.GL_BUFFER_SIZE);
                    if (glSize <= 0) {
                        GL46.nglNamedBufferData(handle, size, 0L, GL46.GL_DYNAMIC_DRAW);
                    }
                    v.arf$setStorageEnsured(true);
                }
                GL46.nglNamedBufferSubData(handle, start, end - start, v.arf$getClientPtr() + start);
            }
            v.arf$setUploaded(end);
            diagUploads++;
            diagBytes += end - start;
            logFlush();
        } catch (Throwable t) {
            if (ORPHAN_UPLOAD && !orphanBroken) {
                // orphan 路径异常：本帧剩余数据改走 subData，后续帧也直接用 subData。
                orphanBroken = true;
                LOGGER.warn("[ARForceMobile] Orphan upload failed, falling back to incremental subData", t);
                v.arf$setUploaded(0);
                flushOne(buffer);
                return;
            }
            broke = true;
            LOGGER.error("[ARForceMobile] Client-buffer upload failed, disabling uploads (rendering may degrade; "
                    + "set arforcemobile mapMode=native to disable AR instead)", t);
            return;
        }
        v.arf$setDirty(false);
    }

    /** orphan 首传后验证存储真的存在（防 MG 对 glNamedBufferData 静默 no-op）。 */
    private static void ensureOrphanWorks(ARClientBufferView v, int handle) {
        if (v.arf$isStorageEnsured()) {
            return;
        }
        v.arf$setStorageEnsured(true);
        int glSize = GL46.glGetNamedBufferParameteri(handle, GL46.GL_BUFFER_SIZE);
        if (glSize <= 0) {
            orphanBroken = true;
            if (!loggedOrphanBroken) {
                loggedOrphanBroken = true;
                LOGGER.warn("[ARForceMobile] glNamedBufferData left no storage (driver quirk), "
                        + "switching client uploads to incremental subData");
            }
        }
    }

    private static void maybeLogDiagnostics() {
        boolean enabled;
        try {
            // 强制 client 模式本身就是诊断动作，统计自动跟随，免去改 toml
            enabled = ARForceConfig.DEBUG_DIAGNOSTICS.get() || ARMapMode.isForceClient();
        } catch (Throwable t) {
            return;
        }
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        if (diagLastNanos == 0L) {
            diagLastNanos = now;
            return;
        }
        long elapsed = now - diagLastNanos;
        if (elapsed < 5_000_000_000L) {
            return;
        }
        LOGGER.info("[ARForceMobile] AR upload stats ({}s): uploads {}, bytes {}, client buffers {}",
                String.format(Locale.ROOT, "%.1f", elapsed / 1e9),
                diagUploads,
                String.format(Locale.ROOT, "%.2f MB", diagBytes / 1048576.0),
                REGISTRY.size());
        diagLastNanos = now;
        diagUploads = 0;
        diagBytes = 0;
    }

    private static void logFlush() {
        if (!loggedFlush) {
            loggedFlush = true;
            LOGGER.info("[ARForceMobile] Client-buffer upload path active (map unavailable; {})",
                    ORPHAN_UPLOAD ? "orphan (per-frame re-spec + payload subData; "
                            + "-Darforcemobile.clientUpload=subdata to A/B)" : "incremental subData");
        }
    }
}
