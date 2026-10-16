package com.arforcemobile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L8 无输入自动化（task-35）：-Darforcemobile.autoEnterWorld=&lt;levelId&gt;
 *
 * 真机上 PCL 的 quickPlay 参数会被 AccessibilityOnboardingScreen（"Welcome to Minecraft!"）挡住，
 * 而窗口输入自动化不可用，所以由本类在客户端 tick 上代劳两件事：
 *   1) 若当前 screen 是 AccessibilityOnboardingScreen：options.onboardAccessibility=false
 *      （1.21.1 官方映射下是 public 字段，见报告 §API）→ options.save() → 切到 TitleScreen；
 *      打一行 "AUTOENTER dismissed onboarding"。
 *   2) 若还没进世界（mc.level == null）且停在 TitleScreen/空 screen：调
 *      Minecraft#createWorldOpenFlows() 得到 net.minecraft.client.gui.screens.worldselection.WorldOpenFlows，
 *      再 openWorld(levelId, onFail) 打开存档；失败 WARN 并每 5 秒重试。
 *      成功（mc.level != null）后打一行 "AUTOENTER in-world: &lt;levelId&gt;" 并彻底停手（幂等）。
 *
 * 与 ARFIfLeakAutoRunner 协作：IFLEAK 的"world ready"判定是它自己的 level != null 检查，
 * 这里不改它的相位语义，两边互不依赖。
 *
 * 默认零副作用：不传属性时 init() 直接 return，**连监听器都不注册**，tick 开销为 0
 * （比"每次 tick 一次 static 判断"更省，口径同 ARBench.init()/ARFProfilerBootstrap.init()）。
 */
public final class ARFAutoEnter {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final String PROPERTY = "arforcemobile.autoEnterWorld";

    /** 目标存档 id（存档目录名）；空 = 未启用。 */
    private static final String LEVEL_ID = System.getProperty(PROPERTY, "").trim();

    /** openWorld 失败后的重试间隔。 */
    private static final long RETRY_NANOS = 5_000_000_000L;

    private static boolean dismissedOnboarding;
    private static boolean enteredWorld;
    private static long lastAttemptNanos;

    private ARFAutoEnter() {
    }

    /** mod 构造期调用；不传属性时零开销（不注册任何监听器）。 */
    public static void init() {
        if (LEVEL_ID.isEmpty()) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ARFAutoEnter::onClientTick);
        LOGGER.info("[ARForceMobile] AUTOENTER armed for level '{}' - waiting for onboarding/title", LEVEL_ID);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (enteredWorld) {
            return; // 幂等：进世界后不再做任何事
        }

        Minecraft minecraft;
        try {
            minecraft = Minecraft.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (minecraft == null) {
            return;
        }

        // 1) 关掉无障碍引导页（1.21.1：Options.onboardAccessibility 是 public 字段，Options.save() 是 public 方法）
        if (!dismissedOnboarding && minecraft.screen instanceof AccessibilityOnboardingScreen) {
            dismissedOnboarding = true;
            try {
                minecraft.options.onboardAccessibility = false;
                minecraft.options.save();
            } catch (Throwable t) {
                LOGGER.warn("[ARForceMobile] AUTOENTER could not persist onboardAccessibility=false", t);
            }
            try {
                minecraft.setScreen(new TitleScreen());
            } catch (Throwable t) {
                LOGGER.warn("[ARForceMobile] AUTOENTER could not switch to TitleScreen, clearing screen", t);
                try {
                    minecraft.setScreen(null);
                } catch (Throwable ignored) {
                    // 只能交给下一 tick 重试
                }
            }
            LOGGER.info("[ARForceMobile] AUTOENTER dismissed onboarding");
            return; // 下一 tick 再进世界
        }

        // 2) 已经在世界里 -> 收工
        if (minecraft.level != null) {
            enteredWorld = true;
            LOGGER.info("[ARForceMobile] AUTOENTER in-world: {}", LEVEL_ID);
            return;
        }

        // 3) 只在主界面/空 screen 上尝试打开存档，每 5 秒最多一次
        Screen screen = minecraft.screen;
        if (screen != null && !(screen instanceof TitleScreen)) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastAttemptNanos < RETRY_NANOS) {
            return;
        }
        lastAttemptNanos = now;

        try {
            // 1.21.1 真实签名：Minecraft#createWorldOpenFlows() ->
            //   net.minecraft.client.gui.screens.worldselection.WorldOpenFlows#openWorld(String, Runnable)
            minecraft.createWorldOpenFlows().openWorld(LEVEL_ID, () ->
                    LOGGER.warn("[ARForceMobile] AUTOENTER openWorld failed for '{}' - retrying in 5s", LEVEL_ID));
        } catch (Throwable t) {
            LOGGER.warn("[ARForceMobile] AUTOENTER openWorld threw for '{}' - retrying in 5s", LEVEL_ID, t);
        }
    }
}
