package com.arforcemobile.mixin;

import com.arforcemobile.ARClientBufferUploader;
import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARSimArgs;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.IDrawMethod;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.basevertex.BaseVertexDrawMethod;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.indirect.IndirectDrawMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 手机环境下选择 AR 的绘制方法（桌面/非手机环境完全不拦截，保持 AR 原生选择）。
 *
 * 选择优先级（1.3.16 起）：
 *   1) 配置文件 draw_method = AUTO | BASEVERTEX | INDIRECT | DEFAULT
 *      （大小写不敏感，非法值按 AUTO）；
 *   2) JVM 属性 -Darforcemobile.forceDrawMethod=indirect|basevertex|default（旧 A/B 开关，保留）；
 *   3) AUTO 逻辑：
 *      a) tier-2 安全闸：safe_draw_method_on_client_buffer=true 且 AR 实际跑在 tier-2
 *         （客户端内存 + 每帧 orphan 上传，见 ARClientBufferUploader.clientModeActive()）
 *         → INDIRECT。原因(实测)：该路径下 BASEVERTEX 的 glDrawElementsBaseVertex 在
 *         Adreno 610 + MobileGlues 2.0.0 上 SIGSEGV
 *         （libmobileglues.so → libGLESv2_adreno.so → memcpy(NULL)）。
 *      b) 否则 MGL（MobileGL/Magma）→ INDIRECT：MGL 上 BASEVERTEX 会导致文本/UI/实体不显示。
 *      c) 其余手机环境 → BASEVERTEX：绕过 INDIRECT 每批次 SSBO 原子 culling compute 的结构性开销。
 *
 * DEFAULT 表示不干预，交还 AR 自身的 coreDrawMethodType 配置。
 * 注意：CoreFeature.getDrawMethod 只在 VanillaBufferEnvironment 构造时调用（每会话 7 次），
 * 返回值被环境对象长期持有，因此安全闸必须在该次调用前就已判定（见
 * ARClientBufferUploader.clientModeActive() 的三条置位来源）。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.CoreFeature")
public class ForceBaseVertexMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** 每条分支只打一次日志（getDrawMethod 每会话被 7 个 VanillaBufferEnvironment 各调一次）。 */
    private static boolean loggedConfig;
    private static boolean loggedProperty;
    private static boolean loggedSafeGate;
    private static boolean loggedMgl;
    private static boolean loggedBaseVertex;

    static {
        LOGGER.info("[ARForceMobile] ForceBaseVertex mixin applied");
    }

    @Inject(method = "getDrawMethod", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$forceBaseVertex(CallbackInfoReturnable<IDrawMethod> cir) {
        if (!ARForceEnvironment.isMobile()) {
            return;
        }

        String choice;
        try {
            choice = arforcemobile$chooseDrawMethod();
        } catch (Throwable t) {
            // 任何异常都不许抛进绘制路径：退回 1.3.15 的旧行为。
            LOGGER.warn("[ARForceMobile] Draw method selection failed, using legacy mobile behavior", t);
            choice = ARForceEnvironment.isMGL() ? "indirect" : "basevertex";
        }

        if ("indirect".equals(choice)) {
            cir.setReturnValue(IndirectDrawMethod.INSTANCE);
        } else if ("basevertex".equals(choice)) {
            cir.setReturnValue(BaseVertexDrawMethod.INSTANCE);
        }
        // "default"：不干预，交还 AR 自身的 coreDrawMethodType。
    }

    /**
     * 选定绘制方法：返回 "indirect" / "basevertex" / "default"。
     * 优先级 = 配置文件 draw_method > -Darforcemobile.forceDrawMethod > AUTO 逻辑。
     */
    private static String arforcemobile$chooseDrawMethod() {
        // 1) 配置文件 draw_method（大小写不敏感，非法值按 AUTO）
        String configured = ARForceConfig.drawMethod();
        if ("INDIRECT".equals(configured)) {
            if (!loggedConfig) {
                loggedConfig = true;
                LOGGER.info("[ARForceMobile] Force draw method -> INDIRECT (config draw_method)");
            }
            return "indirect";
        }
        if ("BASEVERTEX".equals(configured)) {
            if (!loggedConfig) {
                loggedConfig = true;
                LOGGER.info("[ARForceMobile] Force draw method -> BASEVERTEX (config draw_method)");
            }
            return "basevertex";
        }
        if ("DEFAULT".equals(configured)) {
            if (!loggedConfig) {
                loggedConfig = true;
                LOGGER.info("[ARForceMobile] Force draw method -> AR default (config draw_method=DEFAULT)");
            }
            return "default";
        }

        // 2) A/B 测试开关：-Darforcemobile.forceDrawMethod=indirect|basevertex|default
        // -Darforcemobile.forceDrawMethod=... （也覆盖 simulate=<preset> 隐含的同名项）
        String force = ARSimArgs.forceDrawMethod();
        if (force == null) {
            force = "";
        }
        if (force.equals("indirect")) {
            if (!loggedProperty) {
                loggedProperty = true;
                LOGGER.info("[ARForceMobile] Force draw method -> INDIRECT (system property)");
            }
            return "indirect";
        }
        if (force.equals("basevertex")) {
            if (!loggedProperty) {
                loggedProperty = true;
                LOGGER.info("[ARForceMobile] Force draw method -> BASEVERTEX (system property)");
            }
            return "basevertex";
        }
        if (force.equals("default")) {
            if (!loggedProperty) {
                loggedProperty = true;
                LOGGER.info("[ARForceMobile] Force draw method -> AR default (system property)");
            }
            return "default";
        }

        // 3a) tier-2 安全闸（1.3.24 B2 门禁/安全闸解耦）：判据 = 「运行期真的会走客户端内存」。
        //     ARClientBufferUploader.clientModeActive() 的两个来源都是运行期事实或显式强制：
        //       ① MappedBuffer 真的进入客户端内存模式（MappedBufferClientModeMixin.register）；
        //       ② -Darforcemobile.mapMode=client（显式 tier-2 档）。
        //     门禁层不再在"预测要开 tier-2"时替它置位（AvailabilityUtilsMixin），因此
        //     「探针报可用」或「两探针失败但没显式强制」这两条路径下不会再假阳性命中，
        //     也就不会出现「INDIRECT + enableSSBOFix=false」的 MG 启动崩（H-probe B2/R1）。
        //     不变式：INDIRECT ⇔ 运行期真会进客户端内存（或下面 3b 的 MGL）。
        if (ARForceConfig.safeDrawMethodOnClientBuffer() && ARClientBufferUploader.clientModeActive()) {
            if (!loggedSafeGate) {
                loggedSafeGate = true;
                LOGGER.info("[ARForceMobile] Force draw method -> INDIRECT (tier-2 client-memory path; "
                        + "BASEVERTEX crashes on Adreno+MobileGlues). Set safe_draw_method_on_client_buffer=false to override");
            }
            return "indirect";
        }

        // 3b) MGL 强制使用 INDIRECT，避免 BASEVERTEX 下的实体/文本/UI 不显示问题。
        if (ARForceEnvironment.isMGL()) {
            if (!loggedMgl) {
                loggedMgl = true;
                LOGGER.info("[ARForceMobile] Force draw method -> INDIRECT (MobileGL/Magma renderer)");
            }
            return "indirect";
        }

        // 3c) 默认：BASEVERTEX（绕过 INDIRECT 的每批次 SSBO 原子 culling compute）。
        if (!loggedBaseVertex) {
            loggedBaseVertex = true;
            LOGGER.info("[ARForceMobile] Force draw method -> BASEVERTEX (mobile default)");
        }
        return "basevertex";
    }
}
