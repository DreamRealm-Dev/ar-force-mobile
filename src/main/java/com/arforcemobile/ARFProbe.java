package com.arforcemobile;

/**
 * 归因器钩子的注入侧助手（1.3.26 W3）。
 *
 * 存在的理由：21 个钩子 × 2 个注入点（HEAD/RETURN）如果每个都写三行
 * （enabled 判断 + markHook + claim/enter/exit），mixin 会变得又长又容易漏配对。
 * 这里把「默认关 = 一次静态布尔读」和「多候选先到先得（claim）的成对 enter/exit」
 * 收敛成两个静态调用：
 *
 *   HEAD   : ARFProbe.head(H_X, P_X);
 *   RETURN : ARFProbe.tail(H_X, P_X);
 *
 * claim 的成对性：claim(hook, phase) 只有「本 hook 是 owner」时返回 true，
 * 所以 HEAD 与 RETURN 同时用 claim 就天然配对——非 owner 的候选点两边都不动状态，
 * 不会产生 unpaired exit（那会被 ARFProfile 记成 anomalies）。
 *
 * 全部方法 try/catch-free 且零分配：热路径只有静态布尔读 + int 比较 + 数组写。
 */
public final class ARFProbe {

    private ARFProbe() {
    }

    /** 钩子 HEAD：默认关时只花一次静态布尔读；开启时标记命中并（如获授权）开表。 */
    public static void head(int hookId, int phase) {
        if (!ARFProfile.enabled) {
            return;
        }
        ARFProfile.markHook(hookId);
        if (ARFProfile.claim(hookId, phase)) {
            ARFProfile.enter(phase);
        }
    }

    /** 钩子 RETURN：只有 owner 才关表，保证与 head 成对。 */
    public static void tail(int hookId, int phase) {
        if (!ARFProfile.enabled) {
            return;
        }
        if (ARFProfile.claim(hookId, phase)) {
            ARFProfile.exit(phase);
        }
    }

    // ---- entities：实体渲染可能嵌套（乘客），用 LIFO 栈保存每一层进入的阶段 ----
    private static final int[] ENTITY_STACK = new int[256];
    private static int entityStackTop;

    /** 实体渲染 HEAD：contraption 单独记一档；嵌套用栈保证 RETURN 弹到正确的阶段。 */
    public static void entityHead(boolean contraption) {
        if (!ARFProfile.enabled) {
            return;
        }
        ARFProfile.markHook(ARFProfile.H_ENTITIES);
        int phase = contraption ? ARFProfile.P_ENTITIES_CONTRAPTION : ARFProfile.P_ENTITIES;
        if (entityStackTop >= ENTITY_STACK.length) {
            return; // 深到不可能：宁可不计，也不破坏配对
        }
        ENTITY_STACK[entityStackTop++] = phase;
        ARFProfile.enter(phase);
    }

    /** 实体渲染 RETURN：弹出 HEAD 压入的阶段并关表。 */
    public static void entityTail() {
        if (!ARFProfile.enabled) {
            return;
        }
        if (entityStackTop <= 0) {
            return;
        }
        ARFProfile.exit(ENTITY_STACK[--entityStackTop]);
    }

    /**
     * 地形层归类：按 {@code RenderType} 的引用相等判定（与 LevelRenderer.renderLevel
     * 里的分层调用一一对应），识别不了就记 terrain_other。
     */
    public static int terrainPhase(Object renderType) {
        try {
            if (renderType == net.minecraft.client.renderer.RenderType.solid()) {
                return ARFProfile.P_TERRAIN_SOLID;
            }
            if (renderType == net.minecraft.client.renderer.RenderType.cutoutMipped()) {
                return ARFProfile.P_TERRAIN_CUTOUT_MIPPED;
            }
            if (renderType == net.minecraft.client.renderer.RenderType.cutout()) {
                return ARFProfile.P_TERRAIN_CUTOUT;
            }
            if (renderType == net.minecraft.client.renderer.RenderType.translucent()) {
                return ARFProfile.P_TERRAIN_TRANSLUCENT;
            }
        } catch (Throwable ignored) {
            // 归类失败按 other 处理
        }
        return ARFProfile.P_TERRAIN_OTHER;
    }

    /** Create 的 contraption 实体判定（软依赖：Create 不在时缓存 null 并直接返回 false）。 */
    private static Class<?> contraptionClass;
    private static boolean contraptionResolved;

    public static boolean isContraption(Object entity) {
        try {
            if (!contraptionResolved) {
                contraptionResolved = true;
                try {
                    contraptionClass = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
                } catch (ClassNotFoundException e) {
                    contraptionClass = null;
                }
            }
            return contraptionClass != null && contraptionClass.isInstance(entity);
        } catch (Throwable t) {
            return false;
        }
    }
}
