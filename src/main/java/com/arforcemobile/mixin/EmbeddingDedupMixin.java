package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel embedding 矩阵逐 draw 去重（手机环境默认开启）。
 *
 * 反编译结论（Flywheel 1.0.6，javap 实证）：
 * {@code EmbeddedEnvironment.setupDraw(GlProgram)} 每个 draw 都执行
 * {@code setMat4(_flw_modelMatrixUniform, poseComposed)} +
 * {@code setMat3(_flw_normalMatrixUniform, normalComposed)}。
 * poseComposed 按帧确定性合成，帧内逐 draw 不变——同一结构几十个 draw 传
 * 完全相同的值，每次都是 JNI + MG 翻译 + uniform 校验开销。
 *
 * 做法：按 (program handle + 矩阵精确相等) 缓存，相同则跳过。uniform 是
 * per-program 状态，program 不同必放行，语义安全；跨帧旧缓存自然 miss 一次
 * 后更新，无需按帧清理。异常 fail-open；桌面与开关关闭时首行返回。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment")
public abstract class EmbeddingDedupMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    @Shadow(remap = false)
    private Matrix4f poseComposed;

    @Shadow(remap = false)
    private Matrix3f normalComposed;

    @Unique
    private static boolean arf$loggedError;

    @Unique
    private static boolean arf$appliedLogged;

    @Unique
    private boolean arf$hasLast;

    @Unique
    private int arf$lastProgram;

    @Unique
    private Matrix4f arf$lastPose;

    @Unique
    private Matrix3f arf$lastNormal;

    @Inject(method = "setupDraw(Ldev/engine_room/flywheel/backend/gl/shader/GlProgram;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void arforcemobile$dedupEmbedding(GlProgram program, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.FLYWHEEL_EMBEDDING_DEDUP.get()) {
            return;
        }
        if (!arf$appliedLogged) {
            arf$appliedLogged = true;
            LOGGER.info("[ARForceMobile] EmbeddingDedupMixin applied (setupDraw hook reachable)");
        }
        try {
            int handle = program == null ? 0 : program.handle();
            if (arf$hasLast && arf$lastProgram == handle
                    && poseComposed != null && normalComposed != null
                    && arf$lastPose != null && arf$lastPose.equals(poseComposed)
                    && arf$lastNormal != null && arf$lastNormal.equals(normalComposed)) {
                ARFTboTracker.onEmbeddingDedup();
                if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
                    ARFTboTracker.tickDiagnostics();
                }
                ci.cancel();
                return;
            }
            arf$lastProgram = handle;
            if (poseComposed != null) {
                if (arf$lastPose == null) {
                    arf$lastPose = new Matrix4f();
                }
                arf$lastPose.set(poseComposed);
            }
            if (normalComposed != null) {
                if (arf$lastNormal == null) {
                    arf$lastNormal = new Matrix3f();
                }
                arf$lastNormal.set(normalComposed);
            }
            arf$hasLast = true;
        } catch (Throwable t) {
            if (!arf$loggedError) {
                arf$loggedError = true;
                LOGGER.warn("[ARForceMobile] EmbeddingDedup fail-open (passing through)", t);
            }
        }
    }
}
