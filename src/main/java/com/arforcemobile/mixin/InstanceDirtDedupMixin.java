package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFTboTracker;
import dev.engine_room.flywheel.lib.instance.ColoredLitOverlayInstance;
import dev.engine_room.flywheel.lib.instance.PosedInstance;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel 实例脏标记抑制（主凶，手机环境默认开启）。
 *
 * 反编译结论（Flywheel 1.0.6，javap 实证）：
 * - {@code TransformedInstance.setTransform} 只写 {@code pose}，不调
 *   {@code setChanged}；由调用方（Create 视觉层）每 tick 手动调
 *   {@code AbstractInstance.setChanged()}（final，唯一脏标记 choke point）。
 * - {@code InstancedInstancer.updateBuffer} 在 {@code changed} 为空时直接返回，
 *   否则 {@code writeChanged} 经 {@code forEachSetSpan} 逐 span
 *   {@code uploadSpan}——转动时每 actor 每帧的无意义重标记即 ~70% 上传风暴。
 * - 转动结构的刚体运动由 embedding uniform 承载时，局部 pose 每帧重设的值与
 *   上次完全一致，标记纯属浪费。
 *
 * 做法：在 {@code setChanged} HEAD 对比 pose（分量 epsilon 1e-6，视觉无损）+
 * color/light/overlay 快照，全等才跳过；任一变化即放行并更新快照。覆盖
 * TransformedInstance（pose）与 PosedInstance（pose+normal），其余类型放行。
 * 任何异常 fail-open（放行原逻辑）；桌面与开关关闭时首行返回，零介入。
 */
@Mixin(targets = "dev.engine_room.flywheel.lib.instance.AbstractInstance")
public abstract class InstanceDirtDedupMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** 视觉无损阈值：只杀“重设了相同值”的 tick，真实转动增量远大于此。 */
    @Unique
    private static final float ARF_POSE_EPS = 1e-6f;

    @Unique
    private static boolean arf$loggedError;

    @Unique
    private static boolean arf$appliedLogged;

    @Unique
    private boolean arf$hasSnap;

    /** 懒分配：非 Transformed/Posed 实例永不分配，避免每个实例 ~100B 常驻浪费。 */
    @Unique
    private Matrix4f arf$snapPose;

    @Unique
    private Matrix3f arf$snapNormal;

    @Unique
    private boolean arf$snapHasNormal;

    @Unique
    private int arf$snapColor;

    @Unique
    private int arf$snapLight;

    @Unique
    private int arf$snapOverlay;

    @Inject(method = "setChanged()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void arforcemobile$dedupDirtyMark(CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.FLYWHEEL_INSTANCE_DIRT_DEDUP.get()) {
            return;
        }
        if (!arf$appliedLogged) {
            arf$appliedLogged = true;
            LOGGER.info("[ARForceMobile] InstanceDirtDedupMixin applied (setChanged hook reachable)");
        }
        Object self = this;
        Matrix4f pose = null;
        Matrix3f normal = null;
        if (self instanceof TransformedInstance ti) {
            pose = ti.pose;
        } else if (self instanceof PosedInstance pi) {
            pose = pi.pose;
            normal = pi.normal;
        } else {
            return;
        }
        if (pose == null) {
            return;
        }
        try {
            int color = 0;
            int light = 0;
            int overlay = 0;
            ColoredLitOverlayInstance extra = self instanceof ColoredLitOverlayInstance e ? e : null;
            boolean hasExtra = extra != null;
            if (hasExtra) {
                color = (extra.red << 24) | ((extra.green & 0xFF) << 16)
                        | ((extra.blue & 0xFF) << 8) | (extra.alpha & 0xFF);
                light = extra.light;
                overlay = extra.overlay;
            }
            // 先分解布尔再判定：与原短路条件逐位等价（normalEquals 仍只在 pose 相同后才求值），
            // 分类计数是纯附加读取，不改去重语义
            boolean hasSnap = arf$hasSnap;
            boolean poseSame = hasSnap && arf$poseEquals(pose);
            boolean normalSame = !hasSnap || !arf$snapHasNormal || normal == null
                    || (!poseSame || arf$normalEquals(normal));
            boolean extraSame = !hasExtra
                    || (arf$snapColor == color && arf$snapLight == light && arf$snapOverlay == overlay);
            if (hasSnap && poseSame && normalSame && extraSame) {
                ARFTboTracker.onInstanceDedup();
                if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
                    ARFTboTracker.tickDiagnostics();
                }
                ci.cancel();
                return;
            }
            if (!hasSnap) {
                ARFTboTracker.onDirtFirst();
            } else if (!poseSame) {
                ARFTboTracker.onDirtPose();
            } else if (!normalSame) {
                ARFTboTracker.onDirtNormal();
            } else {
                ARFTboTracker.onDirtLight();
            }
            if (arf$snapPose == null) {
                arf$snapPose = new Matrix4f();
            }
            arf$snapPose.set(pose);
            arf$snapHasNormal = normal != null;
            if (normal != null) {
                if (arf$snapNormal == null) {
                    arf$snapNormal = new Matrix3f();
                }
                arf$snapNormal.set(normal);
            }
            if (hasExtra) {
                arf$snapColor = color;
                arf$snapLight = light;
                arf$snapOverlay = overlay;
            }
            arf$hasSnap = true;
        } catch (Throwable t) {
            // fail-open：快照逻辑绝不能破坏渲染，发现一次后只记一次日志
            if (!arf$loggedError) {
                arf$loggedError = true;
                LOGGER.warn("[ARForceMobile] InstanceDirtDedup fail-open (passing through)", t);
            }
        }
    }

    @Unique
    private boolean arf$poseEquals(Matrix4f pose) {
        return Math.abs(pose.m00() - arf$snapPose.m00()) <= ARF_POSE_EPS
                && Math.abs(pose.m01() - arf$snapPose.m01()) <= ARF_POSE_EPS
                && Math.abs(pose.m02() - arf$snapPose.m02()) <= ARF_POSE_EPS
                && Math.abs(pose.m03() - arf$snapPose.m03()) <= ARF_POSE_EPS
                && Math.abs(pose.m10() - arf$snapPose.m10()) <= ARF_POSE_EPS
                && Math.abs(pose.m11() - arf$snapPose.m11()) <= ARF_POSE_EPS
                && Math.abs(pose.m12() - arf$snapPose.m12()) <= ARF_POSE_EPS
                && Math.abs(pose.m13() - arf$snapPose.m13()) <= ARF_POSE_EPS
                && Math.abs(pose.m20() - arf$snapPose.m20()) <= ARF_POSE_EPS
                && Math.abs(pose.m21() - arf$snapPose.m21()) <= ARF_POSE_EPS
                && Math.abs(pose.m22() - arf$snapPose.m22()) <= ARF_POSE_EPS
                && Math.abs(pose.m23() - arf$snapPose.m23()) <= ARF_POSE_EPS
                && Math.abs(pose.m30() - arf$snapPose.m30()) <= ARF_POSE_EPS
                && Math.abs(pose.m31() - arf$snapPose.m31()) <= ARF_POSE_EPS
                && Math.abs(pose.m32() - arf$snapPose.m32()) <= ARF_POSE_EPS
                && Math.abs(pose.m33() - arf$snapPose.m33()) <= ARF_POSE_EPS;
    }

    @Unique
    private boolean arf$normalEquals(Matrix3f normal) {
        return Math.abs(normal.m00() - arf$snapNormal.m00()) <= ARF_POSE_EPS
                && Math.abs(normal.m01() - arf$snapNormal.m01()) <= ARF_POSE_EPS
                && Math.abs(normal.m02() - arf$snapNormal.m02()) <= ARF_POSE_EPS
                && Math.abs(normal.m10() - arf$snapNormal.m10()) <= ARF_POSE_EPS
                && Math.abs(normal.m11() - arf$snapNormal.m11()) <= ARF_POSE_EPS
                && Math.abs(normal.m12() - arf$snapNormal.m12()) <= ARF_POSE_EPS
                && Math.abs(normal.m20() - arf$snapNormal.m20()) <= ARF_POSE_EPS
                && Math.abs(normal.m21() - arf$snapNormal.m21()) <= ARF_POSE_EPS
                && Math.abs(normal.m22() - arf$snapNormal.m22()) <= ARF_POSE_EPS;
    }
}
