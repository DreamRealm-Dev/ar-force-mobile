package com.arforcemobile.mixin;

import com.arforcemobile.ARFDivisorState;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARFLightState;
import com.arforcemobile.ARFOitState;
import com.arforcemobile.ARFProfile;
import com.arforcemobile.ARFSubmitTracker;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import java.util.List;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S5 支撑 + P6 计数：
 * - render HEAD 把 manager 的 vao 交给 ARFDivisorState（InstancedDraw 的
 *   redirect 需要 vao 引用；字段 final，backend 重建时 manager 换新 vao，
 *   setCurrentVao 会自动重置绑定缓存）；
 * - render HEAD frames++（诊断口径与 beginFrames 对照）；
 * - render TAIL 汇总打印提交层 5s 诊断行（每帧一次调用，成本最低）。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.instancing.InstancedDrawManager")
public abstract class InstancedDrawManagerMixin {

    @Shadow(remap = false)
    @Final
    private GlVertexArray vao;

    /** W4（1.3.26）：OIT 集合（Flywheel 私有字段）——本 mixin 是它的**唯一采集点**。 */
    @Shadow(remap = false)
    @Final
    private List<InstancedDraw> oitDraws;

    /**
     * W2（1.3.27）：Flywheel 的排序/重分类门（非 final）。OIT 旁路开关变化时必须置 true，
     * 否则静止场景下 `if (needSort)` 恒 false、分类不刷新，开关"看起来完全没生效"。
     */
    @Shadow(remap = false)
    private boolean needSort;

    @Inject(method = "render(Ldev/engine_room/flywheel/backend/engine/LightStorage;Ldev/engine_room/flywheel/backend/engine/embed/EnvironmentStorage;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$onRenderHead(LightStorage light, EnvironmentStorage environmentStorage, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        ARFDivisorState.setCurrentVao(this.vao);
        ARFSubmitTracker.onFrame();
    }

    @Inject(method = "render(Ldev/engine_room/flywheel/backend/engine/LightStorage;Ldev/engine_room/flywheel/backend/engine/embed/EnvironmentStorage;)V",
            at = @At("HEAD"), remap = false)
    private void arforcemobile$forceRepartition(LightStorage light, EnvironmentStorage environmentStorage, CallbackInfo ci) {
        // W2：OIT 旁路开关变化 -> 强制一次排序/重分类（H-fwfix §2.3.1 的必需项）
        try {
            if (ARFOitState.consumeRepartitionRequest()) {
                this.needSort = true;
            }
        } catch (Throwable ignored) {
            // 静默降级
        }
    }

    @Inject(method = "render(Ldev/engine_room/flywheel/backend/engine/LightStorage;Ldev/engine_room/flywheel/backend/engine/embed/EnvironmentStorage;)V",
            at = @At("TAIL"), remap = false)
    private void arforcemobile$onRenderTail(LightStorage light, EnvironmentStorage environmentStorage, CallbackInfo ci) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }
        // K24：编译流程内触发的降级把缓存清理推迟到这里（帧间安全点）执行
        ARFDivisorState.consumePendingRebuild();
        // W4（1.3.26）：OIT 计数**只在这里读一次**（A14 / H-review §2.2 裁决）：
        //  ① P6 行：ARFSubmitTracker.onOitFrame（窗口内累计 oitFrames/oitDraws）；
        //  ② PROFILE 行：ARFProfile.oitFrame 用同一个布尔值展示，不重复采集。
        try {
            int oitCount = oitDraws == null ? 0 : oitDraws.size();
            ARFSubmitTracker.onOitFrame(oitCount);
            ARFProfile.oitFrame(oitCount > 0);
            // 1.3.27：同一个读取点再喂 W2 的 status（仍是"只读一次、多个出口"）
            ARFOitState.onOitFrame(oitCount);
        } catch (Throwable ignored) {
            // 计数失败绝不影响渲染
        }
        // 1.3.27：I1 的编译失败回退不能在编译流程内 deleteAll，这里消费（帧间安全点）
        ARFLightState.consumePendingRebuild();
        ARFSubmitTracker.tickDiagnostics();
    }
}
