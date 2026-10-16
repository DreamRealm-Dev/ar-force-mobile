package com.arforcemobile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 运行期加速开关（1.3.29）：/arforce gui|text|modernui|translucent on|off|status。
 *
 * 存的是四个 {@code disable_*} toml 键的**运行期覆盖**，三态语义：
 *   null = 跟随 toml；TRUE = 关闭该路 AR 加速；FALSE = 打开该路 AR 加速。
 * 命令面 on = 打开加速（覆盖 FALSE），off = 关闭加速（覆盖 TRUE）。
 *
 * 纪律：
 * - **只改内存**：不写 toml、不碰 AR 自己的配置文件；重启回默认（开测初始状态确定）。
 * - 桌面行为不变：四个消费点各自有 {@code ARForceEnvironment.isMobile()} 门禁，本类不做环境判断。
 * - 生效时机 = 下一帧/下一次绘制：四个消费点（CoreFeature.shouldForceAccelerateTranslucent、
 *   AcceleratedTextRenderingFeature.isEnabled、ModsFeature.shouldAccelerateModernUI、
 *   AcceleratedItemRenderingFeature.shouldAccelerateInGui/shouldUseGuiItemBatching/shouldMergeGuiItemBatches、
 *   CoreFeature.shouldByPassGuiBatching）在 AR 侧都是**逐次调用**读取，无需重建 pipeline、
 *   也不需要像 s5 那样重切 Flywheel backend。
 */
public final class ARFAccelSwitches {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    /** null = 跟随 toml；TRUE = 关闭 AR 加速；FALSE = 打开 AR 加速。 */
    private static volatile Boolean gui;
    private static volatile Boolean text;
    private static volatile Boolean modernui;
    private static volatile Boolean translucent;

    private ARFAccelSwitches() {
    }

    public static Boolean guiDisabled() {
        return gui;
    }

    public static Boolean textDisabled() {
        return text;
    }

    public static Boolean modernuiDisabled() {
        return modernui;
    }

    public static Boolean translucentDisabled() {
        return translucent;
    }

    /** 设置某一路的加速开关；未知名字返回 false（调用方据此报错）。 */
    public static boolean set(String which, boolean enabled) {
        Boolean disable = enabled ? Boolean.FALSE : Boolean.TRUE;
        switch (which) {
            case "gui" -> gui = disable;
            case "text" -> text = disable;
            case "modernui" -> modernui = disable;
            case "translucent" -> translucent = disable;
            default -> {
                return false;
            }
        }
        LOGGER.info("[ARForceMobile] /arforce {} {} -> runtime override (memory only; restart restores toml) | {}",
                which, enabled ? "on" : "off", statusLine());
        return true;
    }

    /** 一行四态 + 来源（toml/runtime）；命令面与 /arforce status 共用。 */
    public static String statusLine() {
        return "accel: "
                + describe("gui", gui, tomlGui())
                + " " + describe("text", text, tomlText())
                + " " + describe("modernui", modernui, tomlModernui())
                + " " + describe("translucent", translucent, tomlTranslucent())
                + " (on = AR accelerated, off = AR bypassed)";
    }

    private static String describe(String name, Boolean override, boolean tomlDisabled) {
        boolean disabled = override != null ? override : tomlDisabled;
        return name + "=" + (disabled ? "OFF" : "ON") + "[" + (override != null ? "runtime" : "toml") + "]";
    }

    // toml 原值只用于状态行的来源标注；配置未加载时按各键默认值（与 getter 的兜底一致）。

    private static boolean tomlGui() {
        try {
            return ARForceConfig.DISABLE_GUI_ACCELERATION.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tomlText() {
        try {
            return ARForceConfig.DISABLE_TEXT_ACCELERATION.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tomlModernui() {
        try {
            return ARForceConfig.DISABLE_MODERNUI_ACCELERATION.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tomlTranslucent() {
        try {
            return ARForceConfig.DISABLE_TRANSLUCENT_ACCELERATION.get();
        } catch (Throwable t) {
            return true;
        }
    }
}
