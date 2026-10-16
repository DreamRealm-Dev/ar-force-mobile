package com.arforcemobile;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W2 OIT 旁路状态（1.3.27，默认关）。
 *
 * 机制（H-fwfix §2.3 + §6.4）：
 * - 在 `InstancedDraw#material()` 的 RETURN 把 ORDER_INDEPENDENT 材质换成
 *   TRANSLUCENT 变体，且 **writeMask=COLOR**（与 OIT 路径/vanilla translucent 一致：不写深度）。
 *   只在分类处改是错的：`MaterialRenderState.setupTransparency(ORDER_INDEPENDENT)` 走
 *   tableswitch 的 default 分支 = 一个混合状态都不设；
 * - 变体按源材质缓存（IdentityHashMap，每材质只构造一次；PipelineProgramKey 不含 transparency
 *   ⇒ 命中同一个已编译程序，零额外编译、不需要 deleteAll）；
 * - 开关状态变化必须强制 `needSort=true`：排序+分类整块被 `if (needSort)` 门控，
 *   否则静止场景下开关"看起来完全没生效"；
 * - OIT 计数只有**一个数据源**（InstancedDrawManagerMixin render TAIL 读一次 `oitDraws.size()`）：
 *   P6/PROFILE 用它，本类的 status 也用它；
 * - fail-open：变体构造/注入异常 -> 本会话自禁用 + WARN 一次，原样返回。
 */
public final class ARFOitState {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** /arforce oit on|off 的内存覆盖；null = 跟随 toml。 */
    private static volatile Boolean sessionOverride;
    private static volatile boolean disabledThisSession;
    private static volatile long bypassedMaterialCalls;
    /** 上一帧 oitDraws.size()（-1 = 还没采过）。 */
    private static volatile int lastOitDraws = -1;
    /** 本会话 OIT 帧数（由同一个读取点喂入）。 */
    private static volatile long oitFrames;
    /** 分类刷新请求：开关变化/首次启用时置位，由 InstancedDrawManagerMixin 消费成 needSort=true。 */
    private static volatile boolean repartitionRequested;

    // ---- 1.3.28：把"每帧每 draw 都要读"的两个值缓存下来 ----
    /** ARForceConfig.flywheelOitBypass() 的缓存（首次读到后固定；/arforce oit 会同步刷新）。 */
    private static volatile Boolean cachedConfigEnabled;
    /** ARForceEnvironment.isMobile() 的缓存。 */
    private static volatile Boolean cachedMobile;

    private static final Map<Material, Material> VARIANTS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private ARFOitState() {
    }

    /** 旁路是否生效（mobile + 开关 + 本会话未自禁用）。 */
    public static boolean bypassActive() {
        try {
            if (disabledThisSession) {
                return false;
            }
            // 1.3.28：默认关时这条路径每帧每 draw 都会走，必须零配置读取——
            // mobile 与 toml 值都只在首次解析一次（volatile 读 + null 检查）。
            Boolean mobile = cachedMobile;
            if (mobile == null) {
                mobile = ARForceEnvironment.isMobile();
                cachedMobile = mobile;
            }
            if (!mobile) {
                return false;
            }
            Boolean override = sessionOverride;
            if (override != null) {
                return override;
            }
            Boolean cached = cachedConfigEnabled;
            if (cached == null) {
                cached = ARForceConfig.flywheelOitBypass();
                cachedConfigEnabled = cached;
            }
            return cached;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 非 OIT 变体（TRANSLUCENT + writeMask=COLOR），按源材质缓存。 */
    public static Material nonOitVariant(Material original) {
        if (original == null) {
            return null;
        }
        Material cached = VARIANTS.get(original);
        if (cached != null) {
            return cached;
        }
        Material variant = SimpleMaterial.builderOf(original)
                .transparency(Transparency.TRANSLUCENT)
                .writeMask(WriteMask.COLOR)
                .build();
        VARIANTS.put(original, variant);
        return variant;
    }

    public static void countBypassed() {
        bypassedMaterialCalls++;
    }

    public static void disableThisSession(Throwable t) {
        if (!disabledThisSession) {
            disabledThisSession = true;
            LOGGER.warn("[ARForceMobile] OIT bypass DISABLED this session ({}). Rendering continues on the OIT path.",
                    t == null ? "unknown error" : t.toString());
        }
    }

    /** 开关变化时请求一次重新分类（消费点 = InstancedDrawManagerMixin HEAD）。 */
    public static void requestRepartition() {
        repartitionRequested = true;
    }

    public static boolean consumeRepartitionRequest() {
        if (!repartitionRequested) {
            return false;
        }
        repartitionRequested = false;
        return true;
    }

    /**
     * OIT 单一采集点（InstancedDrawManagerMixin render TAIL 调用一次）：
     * status 的 `last frame oitDraws` 与 PROFILE 的 oitFrames 都从这一份数据来。
     */
    public static void onOitFrame(int oitDrawCount) {
        lastOitDraws = oitDrawCount;
        if (oitDrawCount > 0) {
            oitFrames++;
        }
    }

    /** /arforce oit on|off：改内存覆盖并请求重分类。 */
    public static void setSessionOverride(boolean enabled) {
        sessionOverride = enabled;
        // 1.3.28：命令同时刷新缓存（缓存 + 覆盖态一起改，保证即时生效且默认路径零读取）
        cachedConfigEnabled = enabled;
        requestRepartition();
        if (enabled) {
            LOGGER.info("[ARForceMobile] OIT bypass ENABLED ({} cached variants, needs a repartition)",
                    VARIANTS.size());
        } else {
            LOGGER.info("[ARForceMobile] OIT bypass DISABLED (repartition requested)");
        }
    }

    public static String statusLine() {
        boolean active = bypassActive();
        return "OIT bypass = " + (active ? "ON" : "OFF")
                + " | toml=" + ARForceConfig.flywheelOitBypass()
                + " | sessionOverride=" + (sessionOverride == null ? "none" : (sessionOverride ? "on" : "off"))
                + " | disabledThisSession=" + (disabledThisSession ? "yes" : "no")
                + " | last frame oitDraws=" + lastOitDraws + (lastOitDraws > 0 ? " (OIT running)" : " (no OIT in scene)")
                + " | bypassedMaterialCalls=" + bypassedMaterialCalls
                + " | variants=" + VARIANTS.size()
                + " | oitFrames(session)=" + oitFrames
                + " | cachedToml=" + (cachedConfigEnabled == null ? "unset" : cachedConfigEnabled)
                + " cachedMobile=" + (cachedMobile == null ? "unset" : cachedMobile);
    }

    /** 判读速查：oitDraws>0 && bypassed=0 ⇒ 开关没生效（先查 needSort 刷新与 mixin 是否 applied）。 */
    public static String diagnoseLine() {
        if (lastOitDraws > 0 && bypassedMaterialCalls == 0 && bypassActive()) {
            return "OIT bypass may NOT be effective: oitDraws>0 but no material was diverted "
                    + "(check needSort repartition / mixin applied)";
        }
        return null;
    }
}
