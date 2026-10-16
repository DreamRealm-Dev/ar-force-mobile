package com.arforcemobile.mixin;

import com.arforcemobile.ARFLightState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * I1 的**独立**失败感知（1.3.27）：Flywheel 的 `Compilation#compile` TAIL 读编译结果，
 * 交给 {@link ARFLightState#onCompileResult} 判定。
 *
 * 关键：**不复用** S5 的 `ARFDivisorState.onCompileResult`（它的判据
 * `name.contains("instancing")` 会把 I1 的编译失败记成 S5 降级）。
 * 本 mixin 只在"我们真的干预过光照档"（appliedName != null）时才动作，
 * 且日志/状态里都带 `lightSmoothness` 字样。
 * 与 DivisorShaderPatchMixin 的 compile TAIL 注入互不干扰（都只读结果、不写同一状态）。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation")
public abstract class LightSmoothnessCompileGuardMixin {

    @Inject(method = "compile(Ldev/engine_room/flywheel/backend/gl/shader/ShaderType;Ljava/lang/String;)Ldev/engine_room/flywheel/backend/compile/core/ShaderResult;",
            at = @At("TAIL"), remap = false, require = 0)
    private static void arforcemobile$onCompileResult(dev.engine_room.flywheel.backend.gl.shader.ShaderType type, String name,
                                                      CallbackInfoReturnable<dev.engine_room.flywheel.backend.compile.core.ShaderResult> cir) {
        ARFLightState.onCompileResult(cir.getReturnValue(), name);
    }
}
