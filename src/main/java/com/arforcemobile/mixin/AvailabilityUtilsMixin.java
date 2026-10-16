package com.arforcemobile.mixin;

import com.arforcemobile.ARForceConfig;
import com.arforcemobile.ARForceEnvironment;
import com.arforcemobile.ARMapMode;
import com.arforcemobile.ARMapProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.utils.AvailabilityUtils")
public class AvailabilityUtilsMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static boolean logged;
    private static boolean nativeLogged;
    private static boolean nativeFallbackLogged;
    private static boolean tier1Logged;
    private static boolean forceClientLogged;
    private static boolean disabledLogged;

    static {
        LOGGER.info("[ARForceMobile] AvailabilityUtils mixin applied");
    }

    @Inject(method = "isAvailable", at = @At("HEAD"), cancellable = true)
    private static void arforcemobile$forceEnable(CallbackInfoReturnable<Boolean> cir) {
        if (!ARForceEnvironment.isMobile() || !ARForceConfig.mobileAutoEnable()) {
            return;
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[ARForceMobile] AvailabilityUtils gate active (map probes + tier ladder decide)");
        }
        // 逐级选档（1.3.5，依据 8Gen1 四档实测跑分）：
        //  1) 普通映射可用 -> tier-1 去位降级映射【主选】：107.3 分，高于原版 84.4
        //     和原生 73.4——Adreno/MG 上持久一致映射强制 CPU 写入落 GPU 可见内存
        //     （uncached/WC），AR 每帧全量细碎写顶点极慢；普通映射落缓存内存、
        //     驱动在使用时同步，反而全面更快；
        //  2) 仅持久映射可用 -> tier-0 原生（理论上限场景，几乎不存在）；
        //  3) 两种映射都不可用 -> 仅当**显式** tier-2 opt-in（-Darforcemobile.mapMode=client，
        //     或 tier2*/610-tier2* preset）才开 tier-2 客户端内存（保命/实验用，实测 63.5 低于原版）。
        //     1.3.24（B2）起 client_buffer_fallback 不再单独放行 AR —— 见下面分支的注释；
        //  4) 其余情况（含 mapMode=native 下持久映射失败）-> AR 关闭 = 原版渲染，
        //     绝不 SIGSEGV。
        boolean nativeOk = ARMapProbe.persistentMapWorks();
        boolean plainOk = ARMapProbe.plainMapWorks();
        if (ARMapMode.isNativeOnly()) {
            if (nativeOk) {
                if (!nativeLogged) {
                    nativeLogged = true;
                    LOGGER.info("[ARForceMobile] mapMode=native: persistent mapping probe passed (tier-0 only)");
                }
                cir.setReturnValue(true);
            } else {
                if (!disabledLogged) {
                    disabledLogged = true;
                    LOGGER.info("[ARForceMobile] mapMode=native and persistent mapping failed -> keeping AR disabled");
                }
                cir.setReturnValue(false);
            }
        } else if (plainOk) {
            if (!tier1Logged) {
                tier1Logged = true;
                LOGGER.info("[ARForceMobile] Enabling AR: plain mapping probe passed (tier-1 primary on GLES, "
                        + "benchmarks faster than persistent coherent mapping)");
            }
            cir.setReturnValue(true);
        } else if (nativeOk) {
            if (!nativeFallbackLogged) {
                nativeFallbackLogged = true;
                LOGGER.info("[ARForceMobile] Enabling AR: persistent mapping probe passed (tier-0 fallback; "
                        + "plain mapping unavailable)");
            }
            cir.setReturnValue(true);
        } else if (ARMapMode.isForceClient()) {
            // 1.3.24（B2 门禁/执行层解耦）：两探针都失败时，**只有显式** tier-2 opt-in
            // （-Darforcemobile.mapMode=client，或 tier2*/610-tier2* preset）才放行 AR。
            //
            // 旧行为（<=1.3.23）：只要 client_buffer_fallback=true 就放行 AR，并在这里
            // **预测性地** markClientModeActive() —— 绘制方法安全闸据此把 BASEVERTEX 换成
            // INDIRECT；而运行期真实映射可用时 MappedBuffer 并不会进入客户端内存
            // （MappedBufferClientModeMixin 只在映射真的失败时才接管），于是形成
            // 「INDIRECT + enableSSBOFix=false」= MG 上 core_block_quad_culling compute
            // 编译失败 -> 启动崩（8G1 可 100% 复现；H-probe B2 / R1）。
            // 现在置位只来自运行期事实（MappedBufferClientModeMixin.register）或本分支的显式强制：
            // 「门禁宣布的档位」与「运行期实际走的档位」不再可能错配。
            if (!forceClientLogged) {
                forceClientLogged = true;
                LOGGER.info("[ARForceMobile] Enabling AR with client-buffer orphan tier "
                        + "(explicit -Darforcemobile.mapMode=client / tier2 preset; both map probes failed)");
            }
            cir.setReturnValue(true);
        } else {
            if (!disabledLogged) {
                disabledLogged = true;
                // 固定格式，供测试者 A/B 自证（升级说明-v2 §1）：不要修改此字符串。
                LOGGER.info("[ARForceMobile] AR disabled: no buffer mapping mode available (client_buffer_fallback=false)");
            }
            cir.setReturnValue(false);
        }
    }
}
