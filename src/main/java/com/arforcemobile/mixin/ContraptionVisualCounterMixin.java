package com.arforcemobile.mixin;

import com.arforcemobile.ARFVisualTracker;
import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * ContraptionVisual 视觉层计数器（S4 计数先行，纯只读诊断，零行为改动）。
 *
 * 目标（Create 6.0.10 javap 实证）：
 * - {@code ContraptionVisual#beginFrame(DynamicVisual$Context)V}：每帧每台必调，
 *   无 limiter、无 isVisible 门控（S2 立项依据）。
 * - {@code #planTick()LPlan;}：TickableVisual Plan 规划入口（actor tick 走 tickableVisuals）。
 * - {@code #setupActor(MutablePair, VirtualRenderWorld)V}：每 actor 一次。
 *
 * 做法：三个 HEAD/TAIL 计数；beginFrame 里对 {@code isVisible(frustum)}
 * 只读调用（不取消、不干预），得 visible/invisible 占比（场外占比）。
 * isVisible 声明在 AbstractEntityVisual（父类，K7：@Shadow 不搜父类，
 * 用 duck-cast 调用）。诊断行每 5s 独立一行，不并入原 8 字段行。
 * 桌面首行返回；任何异常吞掉（诊断钩子绝不干扰渲染）。
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.render.ContraptionVisual")
public abstract class ContraptionVisualCounterMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** 字段在目标类本体（非父类），可 @Shadow；泛型擦除后用 raw List（K8：精确到擦除类型即可）。 */
    @Shadow(remap = false)
    protected List<?> actors;

    @Unique
    private static boolean arf$appliedLogged;

    @Inject(method = "beginFrame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$countBeginFrame(DynamicVisual.Context context, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        try {
            if (!arf$appliedLogged) {
                arf$appliedLogged = true;
                LOGGER.info("[ARForceMobile] ContraptionVisualCounterMixin applied (beginFrame hook reachable)");
            }
            ARFVisualTracker.onBeginFrame();
            if (context != null && context.frustum() != null) {
                // 只读调用：复用父类 isVisible（球测＋迟滞语义与 S2 相同），不干预行为
                boolean visible = ((AbstractEntityVisual<?>) (Object) this).isVisible(context.frustum());
                if (visible) {
                    ARFVisualTracker.onFrameVisible();
                } else {
                    ARFVisualTracker.onFrameInvisible();
                }
            }
            if (ARForceConfig.DEBUG_FLYWHEEL_DIAGNOSTICS.get()) {
                ARFVisualTracker.tickDiagnostics();
            }
        } catch (Throwable ignored) {
            // 诊断钩子绝不干扰渲染
        }
    }

    @Inject(method = "planTick", at = @At("HEAD"), remap = false)
    private void arforcemobile$countPlanTick(CallbackInfoReturnable<Plan<?>> cir) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        try {
            ARFVisualTracker.onPlanTick();
        } catch (Throwable ignored) {
            // 诊断钩子绝不干扰渲染
        }
    }

    @Inject(method = "setupActor", at = @At("TAIL"), remap = false)
    private void arforcemobile$countActor(CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        try {
            ARFVisualTracker.onActorSetup(actors == null ? 0 : actors.size());
        } catch (Throwable ignored) {
            // 诊断钩子绝不干扰渲染
        }
    }
}
