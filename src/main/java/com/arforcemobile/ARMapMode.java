package com.arforcemobile;

/**
 * 映射模式解析。
 *
 * -Darforcemobile.mapMode=client  强制所有 MappedBuffer 走客户端内存 + 上传
 *                                 （在映射正常的设备上复现 tier-2，用于验证）。
 * -Darforcemobile.mapMode=tier1   强制走 tier-1 去位降级映射：即使持久映射可用，
 *                                 也解除后以下降级标志重映射（在 8Gen1 上模拟
 *                                 610 的 tier-1 运行路径，用于跑分对照）。
 * -Darforcemobile.mapMode=native  禁用一切映射回退（仅持久映射）；不可用时关闭 AR。
 * -Darforcemobile.mapMode=auto    默认：普通映射可用即优先 tier-1 降级映射
 *                                 （8Gen1 实测 107.3 分，全面优于原生持久一致映射
 *                                 73.4 与原版 84.4）；否则持久映射可用走原生；
 *                                 都不行且 client_buffer_fallback=true 走客户端内存。
 */
public final class ARMapMode {

    public static final String AUTO = "auto";
    public static final String CLIENT = "client";
    public static final String TIER1 = "tier1";
    public static final String NATIVE = "native";

    private ARMapMode() {
    }

    public static String mode() {
        // -Darforcemobile.mapMode=... 或 -Darforcemobile.simulate=<preset> 中隐含的 mapMode
        String mode = ARSimArgs.mapMode();
        return mode == null ? AUTO : mode;
    }

    /** 是否强制客户端内存模式（tier-2 验证用）。 */
    public static boolean isForceClient() {
        return CLIENT.equals(mode());
    }

    /** 是否强制 tier-1 去位降级映射（8Gen1 模拟 610 路径用）。 */
    public static boolean isForceTier1() {
        return TIER1.equals(mode());
    }

    /** 是否禁用映射回退（严格原生模式）。 */
    public static boolean isNativeOnly() {
        return NATIVE.equals(mode());
    }
}
