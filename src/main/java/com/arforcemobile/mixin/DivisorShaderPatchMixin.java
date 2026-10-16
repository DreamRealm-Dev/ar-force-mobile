package com.arforcemobile.mixin;

import com.arforcemobile.ARFDivisorState;
import com.arforcemobile.ARForceEnvironment;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S5 shader 侧：在 Flywheel 最终 shader 源进入 GL 编译的边界做文本替换。
 *
 * javap 实证：Compilation.compile(ShaderType,String) 内部调
 * GlCompat.safeShaderSource(int, CharSequence)（Flywheel 自己的兼容包装），
 * 源码在 fullSource.toString() 处已定稿——这里拦截即覆盖全部动态生成的
 * GLSL（instance assembler 不走 ShaderSources 资源查找，此前 P0-1 的
 * SourceFinder hook 够不到它）。
 *
 * patch 语义见 ARFDivisorState.patchShaderSource：_flw_unpackInstance 内的
 * texelFetch(_flw_instances, ...) 序列 → _flw_iN attribute 引用。
 * desktop / 开关关闭时原样转发，零改动。
 *
 * 失败感知（R7 教训）：compile TAIL 读 ShaderResult，patched shader 若编译失败
 * （如 R7 的 "Invalid #version"），立即 deactivate S5 并 WARN 恢复指引——
 * 否则 lazy 编译炸掉 InstancingPrograms.instance 后 instancing 后端整机不可用
 * （allLoaded() 只查 instance!=null），用户会被永久困在 off。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation")
public abstract class DivisorShaderPatchMixin {

    @Redirect(method = "compile(Ldev/engine_room/flywheel/backend/gl/shader/ShaderType;Ljava/lang/String;)Ldev/engine_room/flywheel/backend/compile/core/ShaderResult;",
            at = @At(value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/backend/gl/GlCompat;safeShaderSource(ILjava/lang/CharSequence;)V",
                    remap = false),
            remap = false)
    private static void arforcemobile$patchShaderSource(int handle, CharSequence source) {
        String patched = ARFDivisorState.patchShaderSource(source == null ? null : source.toString());
        GlCompat.safeShaderSource(handle, patched);
    }

    @Inject(method = "compile(Ldev/engine_room/flywheel/backend/gl/shader/ShaderType;Ljava/lang/String;)Ldev/engine_room/flywheel/backend/compile/core/ShaderResult;",
            at = @At("TAIL"), remap = false, require = 0)
    private static void arforcemobile$onCompileResult(dev.engine_room.flywheel.backend.gl.shader.ShaderType type, String name,
                                                      CallbackInfoReturnable<dev.engine_room.flywheel.backend.compile.core.ShaderResult> cir) {
        ARFDivisorState.onCompileResult(cir.getReturnValue(), name);
    }
}

