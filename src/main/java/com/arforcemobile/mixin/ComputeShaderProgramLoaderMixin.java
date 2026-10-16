package com.arforcemobile.mixin;

import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARSimArgs;
import com.github.argon4w.acceleratedrendering.core.programs.ComputeShaderProgramLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

/**
 * 当 SSBO 补丁被禁用（-Darforcemobile.enableSSBOFix=false，默认）时，
 * 只跳过包含 atomicCounter 的 culling compute shader，避免 MobileGlues 2.x 编译崩溃；
 * 其他 compute shader（vertex transform / mesh uploading）仍正常编译，
 * 避免 AR 在 BASEVERTEX 下请求 core_block_vertex_transform 等程序时出现
 * “Program is not loaded yet” 崩溃。
 *
 * 非手机环境、或已通过 -Darforcemobile.enableSSBOFix=true 重新启用 SSBO、
 * 或处于 MGL/INDIRECT 分支时，不拦截，维持原行为。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.programs.ComputeShaderProgramLoader")
public class ComputeShaderProgramLoaderMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

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

    private static boolean skipLogged;
    private static boolean passthroughLogged;

    /**
     * lambda$apply$0 是实际在 render thread 上编译/链接全部 compute shader 的合成方法。
     * 在 SSBO 禁用且为 BASEVERTEX 手机环境时，从待编译 map 中移除所有 atomicCounter shader，
     * 保留 transform/uploading 等无 atomicCounter 的 shader。
     */
    @Inject(method = "lambda$apply$0", at = @At("HEAD"), remap = false)
    private static void arforcemobile$skipAtomicCounterShaders(Map map, CallbackInfo ci) {
        if (SSBO_FIX_ENABLED) {
            if (!passthroughLogged && ARForceEnvironment.isMobile()) {
                passthroughLogged = true;
                LOGGER.info("[ARForceMobile] ComputeShaderProgramLoaderMixin: SSBO fix enabled, not skipping atomic-counter shader compilation");
            }
            return;
        }
        if (!ARForceEnvironment.isMobile()) {
            return;
        }

        boolean isMGL = false;
        boolean forcedIndirect = false;
        try {
            isMGL = ARForceEnvironment.isMGL();
            forcedIndirect = ARForceEnvironment.isForcedDrawMethodIndirect();
        } catch (Throwable ignored) {
        }
        if (isMGL || forcedIndirect) {
            if (!skipLogged) {
                skipLogged = true;
                LOGGER.warn("[ARForceMobile] SSBO fix disabled but draw method is INDIRECT (MGL or forced); "
                        + "atomic-counter culling shaders will be compiled and may crash on MobileGlues — "
                        + "use -Darforcemobile.enableSSBOFix=true to avoid");
            }
            return;
        }

        int removed = 0;
        for (Iterator<?> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
            Object value = entry.getValue();
            if (value instanceof ComputeShaderProgramLoader.ShaderSource source
                    && source.source() != null
                    && source.source().contains("atomicCounter")) {
                it.remove();
                removed++;
            }
        }

        if (!skipLogged) {
            skipLogged = true;
            LOGGER.info("[ARForceMobile] Skipped {} atomic-counter compute shader(s) from compilation "
                    + "(SSBO fix disabled, BASEVERTEX mobile); keeping non-atomic transform/uploading shaders",
                    removed);
        }
    }
}
