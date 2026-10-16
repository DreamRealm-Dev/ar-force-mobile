package com.arforcemobile;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GlBuffer 内容版本号登记表 + Flywheel 诊断计数器。
 *
 * TBO（纹理缓冲）的纹理附件跟随缓冲对象本身：只要缓冲内容没变，重复
 * glTexBuffer 就是纯冗余（MG 的 ES<=3.1 模拟路径上更是每次全量重拷）。
 * 以"每个 handle 的上传次数"作为内容版本号：TextureBuffer.bind 时对比
 * 上次绑定时的版本即可判断数据是否变化。
 */
public final class ARFTboTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final Int2LongOpenHashMap HANDLE_VERSIONS = new Int2LongOpenHashMap();
    private static final long WINDOW_NANOS = 5_000_000_000L;

    // 诊断计数器（debug_flywheel_diagnostics 开启时累计；仅渲染线程访问）
    private static long windowStartNanos;
    private static long diagUploads;
    private static long diagUploadBytes;
    private static long diagTboDedupHits;
    private static long diagSectionDedupHits;
    private static long diagInstanceDedupHits;
    private static long diagEmbeddingDedupHits;
    private static long diagLightUploads;
    private static long diagLightUploadSkips;

    // 实例脏放行来源分类（InstanceDirtDedupMixin 放行时归类；仅在开关开启时计数）
    private static long diagDirtFirst;
    private static long diagDirtPose;
    private static long diagDirtNormal;
    private static long diagDirtLight;

    private ARFTboTracker() {
    }

    /** 缓冲内容变化：上传/孤儿化后调用。 */
    public static void bump(int handle, long bytes) {
        HANDLE_VERSIONS.addTo(handle, 1L);
        diagUploads++;
        diagUploadBytes += bytes;
    }

    public static long version(int handle) {
        return HANDLE_VERSIONS.get(handle);
    }

    /** 缓冲删除：清理登记，防止 handle 被驱动回收复用后误判。 */
    public static void invalidate(int handle) {
        HANDLE_VERSIONS.remove(handle);
    }

    public static void onTboDedup() {
        diagTboDedupHits++;
    }

    public static void onSectionDedup() {
        diagSectionDedupHits++;
    }

    public static void onInstanceDedup() {
        diagInstanceDedupHits++;
    }

    public static void onEmbeddingDedup() {
        diagEmbeddingDedupHits++;
    }

    public static void onLightUpload() {
        diagLightUploads++;
    }

    public static void onLightUploadSkip() {
        diagLightUploadSkips++;
    }

    public static void onDirtFirst() {
        diagDirtFirst++;
    }

    public static void onDirtPose() {
        diagDirtPose++;
    }

    public static void onDirtNormal() {
        diagDirtNormal++;
    }

    public static void onDirtLight() {
        diagDirtLight++;
    }

    /** 每 5 秒汇总打印一次（在渲染线程的钩子里顺带调用）。 */
    public static void tickDiagnostics() {
        long now = System.nanoTime();
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
            return;
        }
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        double seconds = (now - windowStartNanos) / 1_000_000_000.0;
        LOGGER.info("[ARForceMobile] Flywheel diagnostics (per {}s): uploads={} bytes={} tboDedupHits={} sectionDedupHits={} instanceDedupHits={} embeddingDedupHits={} lightUploads={} lightSkips={}",
                String.format("%.1f", seconds), diagUploads, diagUploadBytes, diagTboDedupHits, diagSectionDedupHits,
                diagInstanceDedupHits, diagEmbeddingDedupHits, diagLightUploads, diagLightUploadSkips);
        LOGGER.info("[ARForceMobile] Instance dirt pass-through (per {}s): first={} pose={} normal={} light={}",
                String.format("%.1f", seconds), diagDirtFirst, diagDirtPose, diagDirtNormal, diagDirtLight);
        windowStartNanos = now;
        diagUploads = 0;
        diagUploadBytes = 0;
        diagTboDedupHits = 0;
        diagSectionDedupHits = 0;
        diagInstanceDedupHits = 0;
        diagEmbeddingDedupHits = 0;
        diagLightUploads = 0;
        diagLightUploadSkips = 0;
        diagDirtFirst = 0;
        diagDirtPose = 0;
        diagDirtNormal = 0;
        diagDirtLight = 0;
    }
}
