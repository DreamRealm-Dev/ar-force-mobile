package com.arforcemobile;

import net.neoforged.fml.ModList;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 环境识别：只优化“手机渲染环境”，电脑环境保持原样以把本模组开销降到最低。
 *
 * 手机环境判定沿用 AR 自己的黑名单思路：renderer 字符串包含 MobileGlues / gl4es / LTW，
 * 并额外识别常见手机 GPU 名（Mali/Adreno/PowerVR）。桌面 GL 驱动不会命中。
 */
public final class ARForceEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** null = 尚未成功检测；Boolean = 已缓存结果。 */
    private static volatile Boolean mobile;

    /** null = 尚未成功检测；Boolean = 已缓存结果。MGL 指 MobileGL/Magma，不是 MobileGlues。 */
    private static volatile Boolean mgl;

    /** 是否已确认 Sodium/Embeddium/Rubidium 存在（NeoForge mod list 可查时）。 */
    private static volatile Boolean sodiumLikeActive;

    private ARForceEnvironment() {
    }

    /** 是否为手机/GL 翻译层环境。GL 尚未就绪时返回 false，但不缓存失败，后续会重试。 */
    public static boolean isMobile() {
        Boolean cached = mobile;
        if (cached != null) {
            return cached;
        }

        String renderer = getRenderer();
        if (renderer == null) {
            return false;
        }

        String lower = renderer.toLowerCase();
        boolean detected = lower.contains("mobileglues")
                || lower.contains("mobilegl")
                || lower.contains("gl4es")
                || lower.contains("ltw")
                || lower.contains("mali")
                || lower.contains("adreno")
                || lower.contains("powervr");
        mobile = detected;

        LOGGER.info("[ARForceMobile] Renderer = '{}' -> mobile={}", renderer, detected);
        return detected;
    }

    /** 是否为 MobileGL（MGL/Magma）渲染器。注意与 MobileGlues（MG）区分。 */
    public static boolean isMGL() {
        Boolean cached = mgl;
        if (cached != null) {
            return cached;
        }

        String renderer = getRenderer();
        if (renderer == null) {
            return false;
        }

        String lower = renderer.toLowerCase();
        boolean detected = lower.contains("mobilegl") && !lower.contains("mobileglues");
        if (!detected) {
            // Magma 是 MGL DirectVulkan 的渲染器名；MobileGlues 不会叫这个名字。
            detected = lower.contains("magma") && lower.contains("mobilegl");
        }
        mgl = detected;

        LOGGER.info("[ARForceMobile] Renderer = '{}' -> mgl={}", renderer, detected);
        return detected;
    }

    /** 是否通过 -Darforcemobile.forceDrawMethod=indirect 强制走 INDIRECT。 */
    public static boolean isForcedDrawMethodIndirect() {
        return "indirect".equals(ARSimArgs.forceDrawMethod());
    }

    /** Sodium/Embeddium/Rubidium 是否存在并已加载。 */
    public static boolean isSodiumLikeActive() {
        Boolean cached = sodiumLikeActive;
        if (cached != null) {
            return cached;
        }

        try {
            ModList modList = ModList.get();
            if (modList == null) {
                return false;
            }
            boolean loaded = modList.isLoaded("sodium")
                    || modList.isLoaded("embeddium")
                    || modList.isLoaded("rubidium");
            sodiumLikeActive = loaded;
            if (loaded) {
                LOGGER.info("[ARForceMobile] Sodium-like renderer mod detected");
            }
            return loaded;
        } catch (Throwable t) {
            // ModList not ready or not present; treat as absent and don't cache a permanent failure.
            LOGGER.debug("[ARForceMobile] Could not query ModList for Sodium-like mods", t);
            return false;
        }
    }

    private static String getRenderer() {
        // 模拟/调试：-Darforcemobile.simRenderer=<串> 覆盖环境判定读到的 GL_RENDERER。
        String simulated = ARSimArgs.simRenderer();
        if (simulated != null) {
            return simulated;
        }

        try {
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            return renderer == null ? null : renderer.trim();
        } catch (Throwable t) {
            // GL not ready yet; retry later.
            return null;
        }
    }
}
