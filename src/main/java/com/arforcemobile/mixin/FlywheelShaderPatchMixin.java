package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Function;

/**
 * Flywheel transformed 实例着色器的法线补丁（手机环境默认开启）。
 *
 * instance/transformed.vert 原实现：
 *   flw_vertexNormal = mat3(transpose(inverse(i.pose))) * flw_vertexNormal;
 * inverse(mat4) 是逐顶点纯 GPU ALU 开销（约数百次浮点运算/顶点），随结构顶点数
 * 线性放大，在 Adreno 610 这类入门 GPU 上是 Flywheel instancing 路径比原版
 * 渲染慢的主要原因。Create 的 contraption 结构是刚体（无缩放），mat3(i.pose)
 * 数学等价（均匀缩放经后续 normalize 也等价）。
 *
 * 注入点：ShaderSources$SourceFinder.readResource 调用 SourceFile.parse 前的
 * 源文本参数。按 ResourceLocation 精确匹配 flywheel:instance/transformed.vert，
 * 替换目标找不到时（未来版本改动）WARN 一次并放行原文，不破坏加载。
 * 桌面环境与配置关闭时原样放行。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.glsl.ShaderSources$SourceFinder")
public abstract class FlywheelShaderPatchMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final String TARGET = "flywheel:instance/transformed.vert";
    private static final String ORIGINAL = "mat3(transpose(inverse(i.pose)))";
    private static final String REPLACEMENT = "mat3(i.pose)";

    private static boolean loggedPatched;
    private static boolean loggedMissing;

    @ModifyArg(method = "readResource", remap = false, index = 2, at = @At(value = "INVOKE", remap = false,
            target = "Ldev/engine_room/flywheel/backend/glsl/SourceFile;parse(Ljava/util/function/Function;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Ldev/engine_room/flywheel/backend/glsl/LoadResult;"))
    private String arforcemobile$patchNormalTransform(Function<Object, Object> sourceFinder, ResourceLocation name, String source) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.FLYWHEEL_SHADER_NORMAL_FIX.get()) {
            return source;
        }
        if (name == null || !TARGET.equals(name.getNamespace() + ":" + name.getPath())) {
            return source;
        }
        if (!source.contains(ORIGINAL)) {
            if (!loggedMissing) {
                loggedMissing = true;
                LOGGER.warn("[ARForceMobile] transformed.vert: expected normal pattern not found (Flywheel version changed?), leaving shader unpatched");
            }
            return source;
        }
        String patched = source.replace(ORIGINAL, REPLACEMENT);
        if (!loggedPatched) {
            loggedPatched = true;
            LOGGER.info("[ARForceMobile] transformed.vert patched: per-vertex inverse() replaced with mat3 (rigid-transform normal)");
        }
        return patched;
    }
}
