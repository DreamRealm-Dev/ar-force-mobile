package com.arforcemobile.mixin;

import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARSimArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 把 AR 的 culling compute shader 从 atomic_uint/atomicCounter* 重写为 SSBO 原子操作。
 *
 * 必需性(实测):MobileGlues 2.x 不支持 GL_ARB_shader_atomic_counters——
 * ① AR 原生 atomicCounterAdd shader 在 MG 上无法编译(MG 的 GLSL 翻译把调用改成
 *    atomicAdd 但保留 uniform atomic_uint 声明,编译报 no matching overloaded function);
 * ② 不重写则 AR 的 culling shader 在启动加载时直接崩。
 * 注意:mixin 类里不要放非 private 静态字段,否则 Mixin 框架会拒绝应用(InvalidMixinException)。
 *
 * 只在手机环境重写；桌面环境原样返回，避免本模组造成额外行为差异。
 *
 * [TEMP-1.2.3-flickerTest] 为排查生物/物品栏闪烁，SSBO 重写已临时禁用（默认不重写）。
 * 仅保留 AvailabilityUtils 绕过。如需恢复重写，启动参数加：
 *   -Darforcemobile.enableSSBOFix=true
 * 即可回到原 SSBO 路径，无需改代码。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.backends.programs.ComputeShader")
public class ComputeShaderMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    // TEMP: 默认 false = 禁用 SSBO 补丁；true = 启用原重写。便于临时 A/B，无需删代码。
    private static final boolean SSBO_FIX_ENABLED = resolveSsboFix();

    /** 解析顺序：-Darforcemobile.enableSSBOFix（含别名/大小写）> 无则默认 false。 */
    private static boolean resolveSsboFix() {
        try {
            Boolean override = ARSimArgs.enableSsboFix();
            if (override != null) {
                return override;
            }
        } catch (Throwable ignored) {
        }
        return Boolean.parseBoolean(System.getProperty("arforcemobile.enableSSBOFix", "false"));
    }

    private static boolean loggedDisabled;
    private static boolean loggedEnabled;

    static {
        LOGGER.info("[ARForceMobile] ComputeShader mixin applied (SSBO fix enabled={})", SSBO_FIX_ENABLED);
    }

    @ModifyVariable(method = "setShaderSource", at = @At("HEAD"), index = 1, argsOnly = true)
    private String arforcemobile$rewriteAtomicCounters(String source) {
        if (!ARForceEnvironment.isMobile()) {
            return source;
        }
        if (source == null || !source.contains("atomicCounter")) {
            return source;
        }

        // 临时禁用分支：只绕过检测，不改 shader。BASEVERTEX 模式本身不走 culling，
        // 配合 ComputeShaderProgramLoaderMixin 的跳过编译逻辑，可避免 MG 上的编译崩溃。
        if (!SSBO_FIX_ENABLED) {
            if (!loggedDisabled) {
                loggedDisabled = true;
                LOGGER.info("[ARForceMobile] SSBO rewrite DISABLED (temp flicker test) — passing through original atomicCounter source; "
                        + "enable with -Darforcemobile.enableSSBOFix=true");
            }
            return source;
        }

        if (!loggedEnabled) {
            loggedEnabled = true;
            LOGGER.info("[ARForceMobile] SSBO rewrite ENABLED (via -Darforcemobile.enableSSBOFix=true)");
        }

        String rewritten = source.replaceFirst(
                "layout\\s*\\([^)]*\\)\\s*uniform\\s+atomic_uint\\s+(\\w+)\\s*;",
                "layout(binding=0, std430) coherent buffer IndexCounter { uint $1; };");
        rewritten = rewritten.replace("atomicCounterAdd(", "atomicAdd(");
        rewritten = rewritten.replace("atomicCounterExchange(", "atomicExchange(");

        LOGGER.info("[ARForceMobile] Rewrote compute shader source: atomic counters -> SSBO atomics");
        return rewritten;
    }
}
