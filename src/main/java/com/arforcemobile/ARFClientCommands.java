package com.arforcemobile;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * /arforce 游戏内开关命令（用户硬要求：实测包必须游戏内可切，不改 toml 不重启）。
 *
 * 命令只改内存运行时标志：
 * - /arforce s5 on|off|status —— S5 divisor；切换后需重切一次 Flywheel backend
 *   （/flywheel backend off→instancing）触发 shader/VAO 重建才生效（与 toml 默认值
 *   联动的重建链相同）。重启后回 toml 默认，保证开测初始状态确定。
 * - /arforce ubo on|off —— UBO 内容去重，即时生效（无需重建）。
 * - /arforce gui|text|modernui|translucent on|off|status —— P2 运行期加速开关（task-38）：
 *   on = 打开 AR 对该路的加速，off = 关闭（= 覆盖对应 disable_* toml 键），只改内存，
 *   重启回 toml 默认；四个消费点都是逐次调用读取，下一帧生效，无需重建 pipeline/重切 backend。
 * - /arforce status —— 一行总状态（含降级原因）。
 *
 * 桌面拒绝执行（isMobile 闸门，§8.4-1）。
 */
public final class ARFClientCommands {

    private ARFClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(net.minecraft.commands.Commands.literal("arforce")
                .then(net.minecraft.commands.Commands.literal("s5")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> {
                            ARFDivisorState.setRuntimeEnabled(true);
                            return feedback(ctx.getSource(), "S5 divisor = ON"
                                    + " - re-switch the Flywheel backend once (off -> instancing) to rebuild the pipeline");
                        }))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> {
                            ARFDivisorState.setRuntimeEnabled(false);
                            return feedback(ctx.getSource(), "S5 divisor = OFF"
                                    + " - re-switch the Flywheel backend once (off -> instancing) to rebuild the pipeline");
                        }))
                        .then(net.minecraft.commands.Commands.literal("status").executes(ctx ->
                                feedback(ctx.getSource(), ARFDivisorState.statusLine()))))
                .then(net.minecraft.commands.Commands.literal("ubo")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> {
                            ARFDivisorState.setUboRuntimeOverride(true);
                            return feedback(ctx.getSource(), "UBO dedup = ON (immediate)");
                        }))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> {
                            ARFDivisorState.setUboRuntimeOverride(false);
                            return feedback(ctx.getSource(), "UBO dedup = OFF (immediate)");
                        })))
                .then(net.minecraft.commands.Commands.literal("light")
                        .then(net.minecraft.commands.Commands.literal("tri_linear").executes(ctx -> {
                            boolean ok = ARFLightState.setRuntime("tri_linear");
                            return feedback(ctx.getSource(), ok
                                    ? "lightSmoothness = tri_linear - takes effect next frame (pipeline cache cleared)"
                                    : "lightSmoothness apply failed - see the log (lastError in /arforce light status)");
                        }))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> {
                            ARFLightState.setRuntime("off");
                            return feedback(ctx.getSource(), "lightSmoothness released to Flywheel config ("
                                    + ARFLightState.readback() + ")");
                        }))
                        .executes(ctx -> feedback(ctx.getSource(), ARFLightState.statusLine())))
                .then(net.minecraft.commands.Commands.literal("oit")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> {
                            ARFOitState.setSessionOverride(true);
                            return feedback(ctx.getSource(), "OIT bypass = ON - takes effect next frame "
                                    + "(no deleteAll / no backend re-switch needed)");
                        }))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> {
                            ARFOitState.setSessionOverride(false);
                            String diagnose = ARFOitState.diagnoseLine();
                            return feedback(ctx.getSource(), "OIT bypass = OFF" + (diagnose == null ? "" : " | " + diagnose));
                        }))
                        .executes(ctx -> feedback(ctx.getSource(), ARFOitState.statusLine())))
                // L5（task-32）：AR x ImmediatelyFast 泄漏修复的命令面，供无输入 A/B 自测。
                // 只改本模组自己的运行时状态（ARFIfLeakState），不动任何配置键。
                .then(net.minecraft.commands.Commands.literal("ifleak")
                        .then(net.minecraft.commands.Commands.literal("status").executes(ctx -> {
                            ARFIfLeakState.logStatus("cmd");
                            return feedback(ctx.getSource(), ARFIfLeakState.statusLine());
                        }))
                        .then(net.minecraft.commands.Commands.literal("fix")
                                .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> {
                                    ARFIfLeakState.setFixRuntime(true);
                                    return feedback(ctx.getSource(), "IF-leak key fix = ON (runtime override)"
                                            + " | " + ARFIfLeakState.statusLine());
                                }))
                                .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> {
                                    ARFIfLeakState.setFixRuntime(false);
                                    return feedback(ctx.getSource(), "IF-leak key fix = OFF (runtime override)"
                                            + " | " + ARFIfLeakState.statusLine());
                                })))
                        .then(net.minecraft.commands.Commands.literal("reset").executes(ctx -> {
                            ARFIfLeakState.reset();
                            return feedback(ctx.getSource(), "IF-leak counters cleared + runtime override released"
                                    + " (back to -Darforcemobile.ifKeyFix) | " + ARFIfLeakState.statusLine());
                        }))
                        .executes(ctx -> {
                            ARFIfLeakState.logStatus("cmd");
                            return feedback(ctx.getSource(), ARFIfLeakState.statusLine());
                        }))
                .then(net.minecraft.commands.Commands.literal("gui")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx ->
                                accel(ctx.getSource(), "gui", true)))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx ->
                                accel(ctx.getSource(), "gui", false)))
                        .then(net.minecraft.commands.Commands.literal("status").executes(ctx ->
                                feedback(ctx.getSource(), ARFAccelSwitches.statusLine()))))
                .then(net.minecraft.commands.Commands.literal("text")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx ->
                                accel(ctx.getSource(), "text", true)))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx ->
                                accel(ctx.getSource(), "text", false)))
                        .then(net.minecraft.commands.Commands.literal("status").executes(ctx ->
                                feedback(ctx.getSource(), ARFAccelSwitches.statusLine()))))
                .then(net.minecraft.commands.Commands.literal("modernui")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx ->
                                accel(ctx.getSource(), "modernui", true)))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx ->
                                accel(ctx.getSource(), "modernui", false)))
                        .then(net.minecraft.commands.Commands.literal("status").executes(ctx ->
                                feedback(ctx.getSource(), ARFAccelSwitches.statusLine()))))
                .then(net.minecraft.commands.Commands.literal("translucent")
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx ->
                                accel(ctx.getSource(), "translucent", true)))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx ->
                                accel(ctx.getSource(), "translucent", false)))
                        .then(net.minecraft.commands.Commands.literal("status").executes(ctx ->
                                feedback(ctx.getSource(), ARFAccelSwitches.statusLine()))))
                .then(net.minecraft.commands.Commands.literal("status").executes(ctx ->
                        feedback(ctx.getSource(), ARFDivisorState.statusLine() + " | " + ARFAccelSwitches.statusLine()))));
    }

    /** P2（task-38）：/arforce gui|text|modernui|translucent on|off —— 只改内存的运行期覆盖。 */
    private static int accel(net.minecraft.commands.CommandSourceStack source, String which, boolean enabled) {
        if (!ARFAccelSwitches.set(which, enabled)) {
            return feedback(source, "unknown switch: " + which);
        }
        return feedback(source, which + " acceleration = " + (enabled ? "ON" : "OFF")
                + " (runtime override, memory only; restart restores toml) | " + ARFAccelSwitches.statusLine());
    }

    private static int feedback(net.minecraft.commands.CommandSourceStack source, String message) {
        if (!ARForceEnvironment.isMobile()) {
            source.sendFailure(Component.literal("[ARForceMobile] /arforce commands are mobile-only."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[ARForceMobile] " + message).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
