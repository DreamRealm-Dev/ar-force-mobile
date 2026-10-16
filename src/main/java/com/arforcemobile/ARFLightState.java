package com.arforcemobile;

import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.compile.core.ShaderResult;
import dev.engine_room.flywheel.impl.NeoForgeFlwConfig;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * I1 Flywheel 光照档降档（1.3.27，默认 off = 不干预）。
 *
 * 纪律（H-fwfix §1.2/§1.3 + task-22 裁决）：
 * - `lightSmoothness` 是**编译期**读取的 ⇒ 运行期改**必须** {@link PipelineCompiler#deleteAll()}，
 *   否则 100% 无效；**启动期 apply 不需要**（那时还没有 instancing 程序被编译）；
 * - 时机不能早于 Flywheel 的 config 加载（`EnumValue.set()` 有 loadedConfig 前置）：
 *   本类由 ARForceMobile 在**客户端第一帧**调用 {@link #applyAtStartup()}（见报告 §13 的偏差说明，
 *   等效于规格里的 FMLClientSetupEvent，且不需要 mod 事件总线）；
 * - `set()` **只改内存、不写盘** ⇒ 不污染玩家的 flywheel-client.toml，重启回玩家自己的值；
 * - `/arforce light status` **只回读 `EnumValue.get()`**（不是我们的目标值），不一致时标注
 *   `(overridden by /flywheel)`；
 * - 失败感知**独立于 S5**（绝不复用 ARFDivisorState 的 `name.contains("instancing")` 判据），
 *   日志里带 `lightSmoothness` 字样；L4 命中时立即回 SMOOTH，`deleteAll()` 延迟到帧间安全点；
 * - 全程 try/catch 静默降级，绝不让 mod 启动失败。
 */
public final class ARFLightState {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** 我们最后一次成功写入的名字；null = 从未干预（默认态）。 */
    private static volatile String appliedName;
    /** 运行期覆盖（/arforce light 用）；null = 跟随 toml。 */
    private static volatile String sessionOverride;
    private static volatile String lastError;
    private static volatile boolean disabledThisSession;
    private static volatile boolean pendingRebuild;
    private static boolean startupApplied;

    private ARFLightState() {
    }

    /** 客户端第一帧调用一次：按 toml（或运行期覆盖）在启动期 apply。 */
    public static void applyAtStartup() {
        if (startupApplied) {
            return;
        }
        startupApplied = true;
        try {
            if (!ARForceEnvironment.isMobile() || disabledThisSession) {
                return;
            }
            String want = effectiveTarget();
            if (want == null) {
                return; // off：不触碰 Flywheel
            }
            apply(want, false);
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    /** 运行期切换（/arforce light tri_linear|off）：返回是否真的改到了值（改动会清管线缓存）。 */
    public static boolean setRuntime(String mode) {
        try {
            String want = "tri_linear".equals(mode) ? "tri_linear" : "off";
            sessionOverride = "off".equals(want) ? "auto" : want;
            if ("off".equals(want)) {
                // 交回 Flywheel 自己的值（不一定等于 SMOOTH，取决于玩家的 toml）
                sessionOverride = null;
                String current = readback();
                LightSmoothness back = "smooth".equals(current) ? LightSmoothness.SMOOTH
                        : "tri_linear".equals(current) ? LightSmoothness.TRI_LINEAR
                        : "flat".equals(current) ? LightSmoothness.FLAT
                        : "smooth_inner_face_corrected".equals(current) ? LightSmoothness.SMOOTH_INNER_FACE_CORRECTED
                        : LightSmoothness.SMOOTH;
                writeValue(back);
                PipelineCompiler.deleteAll();
                appliedName = null;
                LOGGER.info("[ARForceMobile] lightSmoothness released to Flywheel config ({})", current);
                return true;
            }
            boolean ok = apply(want, true);
            LOGGER.info("[ARForceMobile] lightSmoothness runtime switch -> {} ({})", want, ok ? "applied" : "failed");
            return ok;
        } catch (Throwable t) {
            lastError = t.toString();
            LOGGER.warn("[ARForceMobile] lightSmoothness runtime switch failed: {}", t.toString());
            return false;
        }
    }

    private static String effectiveTarget() {
        String override = sessionOverride;
        if (override != null) {
            return "off".equals(override) ? null : override;
        }
        String configured = ARForceConfig.flywheelLightSmoothness();
        return "off".equals(configured) ? null : configured;
    }

    /** 真正写入 Flywheel 的 EnumValue（target != null）。 */
    private static boolean apply(String want, boolean runtime) {
        try {
            // L1：Flywheel 未装/类缺失 -> 什么都不做
            if (!ModList.get().isLoaded("flywheel")) {
                if (runtime) {
                    LOGGER.warn("[ARForceMobile] lightSmoothness apply skipped: flywheel is not loaded");
                }
                return false;
            }
            LightSmoothness target = "tri_linear".equals(want) ? LightSmoothness.TRI_LINEAR : LightSmoothness.SMOOTH;
            if (readbackEnum() == target) {
                appliedName = want;
                return true;
            }
            writeValue(target);
            if (runtime) {
                PipelineCompiler.deleteAll(); // 编译期读取：运行期改必须清缓存
            }
            appliedName = want;
            lastError = null;
            LOGGER.info("[ARForceMobile] lightSmoothness applied{}: {} (config file NOT modified)",
                    runtime ? " (runtime, pipeline cache cleared)" : " at startup", want);
            return true;
        } catch (Throwable t) {
            lastError = t.toString();
            LOGGER.warn("[ARForceMobile] lightSmoothness apply failed, staying on Flywheel default: {}", t.toString());
            return false;
        }
    }

    private static void writeValue(LightSmoothness target) {
        NeoForgeFlwConfig.INSTANCE.client.backendConfig.lightSmoothness.set(target);
    }

    private static LightSmoothness readbackEnum() {
        return NeoForgeFlwConfig.INSTANCE.client.backendConfig.lightSmoothness.get();
    }

    /** 只读回读（给 /arforce light status）：拿不到返回 "unavailable"。 */
    public static String readback() {
        try {
            LightSmoothness value = readbackEnum();
            return value == null ? "unavailable" : value.getSerializedName();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * L4 失败感知（LightSmoothnessCompileGuardMixin 的 compile TAIL 调用）：
     * 只在我们真的干预过（appliedName != null）时判定；失败立即回 SMOOTH，
     * `deleteAll()` 延迟到帧间安全点（InstancedDrawManagerMixin TAIL 消费）。
     */
    public static void onCompileResult(ShaderResult result, String name) {
        try {
            if (appliedName == null || result == null) {
                return;
            }
            result.unwrap();
        } catch (Throwable t) {
            String reason = "lightSmoothness variant failed to compile: " + name + " (" + t.getMessage() + ")";
            disabledThisSession = true;
            lastError = reason;
            try {
                writeValue(LightSmoothness.SMOOTH);
                appliedName = null;
                pendingRebuild = true;
            } catch (Throwable ignored) {
                // 回退失败：只报一次错
            }
            LOGGER.warn("[ARForceMobile] {} -> reverting lightSmoothness to SMOOTH (run /flywheel backend lightSmoothness "
                    + "smooth if anything still looks wrong)", reason);
        }
    }

    /** 帧间安全点消费（InstancedDrawManagerMixin TAIL）：编译流程内不能 deleteAll。 */
    public static void consumePendingRebuild() {
        if (!pendingRebuild) {
            return;
        }
        pendingRebuild = false;
        try {
            PipelineCompiler.deleteAll();
        } catch (Throwable ignored) {
            // 静默降级
        }
    }

    public static String statusLine() {
        return "light = " + readback() + " (readback from flywheel)"
                + " | applied=" + (appliedName == null ? "none" : appliedName)
                + " | toml=" + ARForceConfig.flywheelLightSmoothness()
                + " | sessionOverride=" + (sessionOverride == null ? "none" : sessionOverride)
                + " | disabledThisSession=" + (disabledThisSession ? "yes" : "no")
                + (lastError == null ? "" : " | lastError=" + lastError);
    }
}
