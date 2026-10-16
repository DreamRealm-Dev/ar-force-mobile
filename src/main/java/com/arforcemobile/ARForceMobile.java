package com.arforcemobile;

import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ARForceMobile.MODID)
public class ARForceMobile {
    public static final String MODID = "arforcemobile";

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** true/false = 已能判定 Accelerated Rendering 是否加载；null = 此刻无法判定（不报警，避免误报）。 */
    private static Boolean isAcceleratedRenderingLoaded() {
        try {
            net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.get();
            if (modList == null || modList.getMods().isEmpty()) {
                return null;
            }
            return modList.isLoaded("acceleratedrendering");
        } catch (Throwable t) {
            return null;
        }
    }

    public ARForceMobile() {
        var container = ModLoadingContext.get().getActiveContainer();
        if (container != null) {
            container.registerConfig(ModConfig.Type.CLIENT, ARForceConfig.SPEC);
        }
        // 常开诊断：本 JVM 实际收到的 -D 参数与 arforcemobile.* 属性（用于排查参数没生效）。
        Boolean arLoaded = isAcceleratedRenderingLoaded();
        LOGGER.info("[ARForceMobile] JVM report: {} | AcceleratedRendering loaded: {}", ARSimArgs.jvmReport(),
                arLoaded == null ? "unknown" : arLoaded.toString());
        // 模拟/调试参数摘要：只有显式传了 -Darforcemobile.* 模拟参数时才会打印一行。
        String sim = ARSimArgs.summary();
        if (sim != null) {
            LOGGER.info("[ARForceMobile] Sim/override state: {}", sim);
        }
        // 误配置守卫：模拟参数只有在 Accelerated Rendering 真的加载时才有意义 (1.3.21)。
        if (Boolean.FALSE.equals(arLoaded) && sim != null) {
            LOGGER.warn("[ARForceMobile] Accelerated Rendering is NOT loaded in this instance: every -Darforcemobile.* "
                    + "simulation argument has NO effect (all AR-side mixins were skipped: look for "
                    + "'@Mixin target com.github.argon4w.acceleratedrendering... was not found arforcemobile.mixins.json' "
                    + "warnings). Put acceleratedrendering-*.jar back into mods/ before running tier/sim tests.");
        } else if (Boolean.FALSE.equals(arLoaded)) {
            LOGGER.info("[ARForceMobile] Accelerated Rendering not detected -> only Flywheel-side optimizations are active.");
        }
        // /arforce 游戏内开关（S5 divisor / UBO dedup），桌面执行时 isMobile 拒绝
        NeoForge.EVENT_BUS.addListener(ARFClientCommands::register);
        // L5（task-32）：无输入 A/B/A/B 自动分相自测。不传 -Darforcemobile.ifleakAuto=<秒> 时
        // 连监听器都不注册（与 1.3.29-ifleak 完全一致）；口径同 ARBench.init()/ARFProfilerBootstrap.init()。
        ARFIfLeakAutoRunner.init();
        // L8（task-35）：无输入自动进存档（-Darforcemobile.autoEnterWorld=<levelId>）。
        // 不传属性时 init() 直接 return，不注册监听器、tick 零开销。
        ARFAutoEnter.init();
        // 帧时基准（1.3.23，默认关）：只有 -Darforcemobile.bench=true 时才注册事件/shutdown hook，
        // 不开时 ARBench.init() 不会被调用，行为与 1.3.22 完全一致。
        if (ARBench.enabled()) {
            ARBench.init();
        }
        // W3 帧阶段归因器（1.3.26，默认关）：只有 -Darforcemobile.profile=true 时才注册事件/输出。
        ARFProfilerBootstrap.init();
        // I1（1.3.27，默认 off）：客户端**第一帧**apply 光照档（晚于 Flywheel config 加载、
        // 早于第一个世界的 pipeline 编译；等效于规格里的 FMLClientSetupEvent，见报告 §13 偏差说明）。
        // 1.3.28：改成**一次性实例监听器**——第一次分发后自注销，稳态零成本（见 ARFLightStartupListener）。
        ARFLightStartupListener.register();
    }
}
