package com.arforcemobile;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ar-force-mobile 自己的客户端配置。
 *
 * 设计原则：
 * - 默认值全部以“手机环境优化”为准；
 * - 电脑环境不会被这些开关影响（环境判断在 ARForceEnvironment 中）；
 * - 所有可关闭项都提供开关，方便不想自动改 AR 行为的用户。
 */
public final class ARForceConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("arforcemobile");

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 手机环境下是否强制让 AR 可用。默认 true。 */
    public static final ModConfigSpec.BooleanValue MOBILE_AUTO_ENABLE = BUILDER
            .comment("Mobile only. When true, ar-force-mobile forces Accelerated Rendering to be available on MobileGlues/gl4es/LTW.",
                    "Desktop is never affected.")
            .define("mobile_auto_enable", true);

    /**
     * 强制 AR 使用的绘制方法（手机环境，最高优先级）。
     *
     * 取值大小写不敏感：AUTO | BASEVERTEX | INDIRECT | DEFAULT，非法值按 AUTO。
     * - AUTO（默认）：沿用本模组的自动判断——正常情况 BASEVERTEX（绕过 INDIRECT 每批次
     *   SSBO 原子 culling compute 的结构性开销）；但若 AR 实际运行在 tier-2
     *   （客户端内存 + 每帧 orphan 上传）路径、且 safe_draw_method_on_client_buffer=true，
     *   则改用 INDIRECT：BASEVERTEX 的 glDrawElementsBaseVertex 在 Adreno 610 +
     *   MobileGlues 2.0.0 上实测 SIGSEGV（libmobileglues.so → libGLESv2_adreno.so → memcpy(NULL)）。
     * - BASEVERTEX / INDIRECT：无条件强制，供 A/B 测试。
     * - DEFAULT：不干预，交还 AR 自身的 coreDrawMethodType 配置。
     *
     * 优先级：本配置 > -Darforcemobile.forceDrawMethod > AUTO 逻辑。桌面环境不受影响。
     */
    public static final ModConfigSpec.ConfigValue<String> DRAW_METHOD = BUILDER
            .comment("Mobile only. Draw method forced by ar-force-mobile: AUTO | BASEVERTEX | INDIRECT | DEFAULT.",
                    "AUTO (default): BASEVERTEX normally, but INDIRECT while AR runs from the tier-2",
                    "client-memory (per-frame orphan upload) path, where glDrawElementsBaseVertex",
                    "segfaults on Adreno + MobileGlues 2.x. See safe_draw_method_on_client_buffer.",
                    "DEFAULT: leave the draw method to Accelerated Rendering's own config.",
                    "Case-insensitive; invalid values fall back to AUTO.",
                    "Priority: this option > -Darforcemobile.forceDrawMethod > AUTO logic.",
                    "Desktop is never affected.")
            .define("draw_method", "AUTO");

    /**
     * tier-2（客户端内存 + 每帧 orphan 上传）路径下的绘制方法安全闸。默认 true。
     *
     * 只在 draw_method=AUTO（且未设置 -Darforcemobile.forceDrawMethod）时生效：命中时把
     * AUTO 的选择从 BASEVERTEX 换成 INDIRECT，避开 Adreno + MobileGlues 2.x 上
     * BASEVERTEX 的空指针 memcpy 崩溃。想复现旧行为做 A/B 时置 false。
     */
    public static final ModConfigSpec.BooleanValue SAFE_DRAW_METHOD_ON_CLIENT_BUFFER = BUILDER
            .comment("Mobile only. When true, the AUTO draw method uses INDIRECT instead of BASEVERTEX",
                    "while AR actually runs from the tier-2 client-memory path (client_buffer_fallback=true",
                    "or -Darforcemobile.mapMode=client), where BASEVERTEX crashes on Adreno + MobileGlues 2.x.",
                    "Set false only to reproduce the old behavior for A/B testing.",
                    "Desktop is never affected.")
            .define("safe_draw_method_on_client_buffer", true);

    /** 手机 + Sodium 存在时，是否把 AR 的 force_translucent_acceleration 热注入为 DISABLED。默认 true。 */
    public static final ModConfigSpec.BooleanValue DISABLE_TRANSLUCENT_ACCELERATION = BUILDER
            .comment("Mobile + Sodium/Embeddium only. When true, hot-injects AR's force_translucent_acceleration to DISABLED",
                    "so translucent rendering falls back to Sodium/vanilla instead of AR's forced translucent pipeline.",
                    "Desktop is never affected.")
            .define("disable_translucent_acceleration", true);

    /** 手机环境下是否把 AR 的 debug_context 热注入为 DISABLED。默认 true。 */
    public static final ModConfigSpec.BooleanValue DISABLE_DEBUG_CONTEXT = BUILDER
            .comment("Mobile only. When true, hot-injects AR's debug_context to DISABLED.",
                    "This removes AR's debug/validation overhead on phones.",
                    "Desktop is never affected.")
            .define("disable_debug_context", true);

    /**
     * 手机环境下是否强制关闭 AR 的文本加速（告示牌/实体名牌等 BakedGlyph 文本）。
     *
     * 历史：旧版 MG/gl4es 在强切 BASEVERTEX 后文本会闪烁/不显示，因此 1.2.x 曾默认 true。
     * 现已恢复默认 false（临时恢复文本加速）：
     * - 崩溃（老 Adreno 空映射）由 map probe/降级兜底，与文本加速无关；
     * - 闪烁症状与实体闪烁同类，entity_sync_fix 的 drawBuffers 全量 barrier 现在已能生效，
     *   可能一并覆盖文本路径；
     * - 若旧设备上文本闪烁/不显示复发，把 arforcemobile-client.toml 里
     *   disable_text_acceleration 改回 true 即可（注意：旧安装的 toml 里已是 true，
     *   需手动改 false 或删除该文件重新生成）。
     */
    public static final ModConfigSpec.BooleanValue DISABLE_TEXT_ACCELERATION = BUILDER
            .comment("Mobile only. When true, hot-disables Accelerated Rendering's text acceleration.",
                    "Was default true (BASEVERTEX flicker workaround on old MG/gl4es); now default false.",
                    "The drawBuffers memory barrier (entity_sync_fix) may already cover the flicker.",
                    "Set true only if text flicker/missing returns. Desktop is never affected.")
            .define("disable_text_acceleration", false);

    /**
     * 手机环境下是否强制关闭 AR 对 ModernUI 文本/UI 的加速。
     * 同 disable_text_acceleration：1.2.6 起默认恢复（false），闪烁复发再改回 true。
     */
    public static final ModConfigSpec.BooleanValue DISABLE_MODERNUI_ACCELERATION = BUILDER
            .comment("Mobile only. When true, hot-disables AR's ModernUI text/UI acceleration.",
                    "Was default true; now default false (temporarily restored).",
                    "Set true only if ModernUI-driven UI flickers/goes missing. Desktop is never affected.")
            .define("disable_modernui_acceleration", false);

    /**
     * 手机环境下是否强制关闭 AR 的 GUI（容器/界面）加速。
     * 同 disable_text_acceleration：1.2.6 起默认恢复（false），同时不再强制 bypass GUI batching。
     */
    public static final ModConfigSpec.BooleanValue DISABLE_GUI_ACCELERATION = BUILDER
            .comment("Mobile only. When true, hot-disables AR's GUI/item-in-GUI acceleration",
                    "and forces GUI batching bypass.",
                    "Was default true; now default false (temporarily restored).",
                    "Set true only if container/GUI rendering flickers/goes missing. Desktop is never affected.")
            .define("disable_gui_acceleration", false);

    /** 手机环境下是否强制关闭 AR 的实体加速。默认 false，用于性能 A/B。 */
    public static final ModConfigSpec.BooleanValue DISABLE_ENTITY_ACCELERATION = BUILDER
            .comment("Mobile only. When true, hot-disables AR's entity acceleration.",
                    "Use this to test whether AR entity acceleration helps or hurts on a translation layer.",
                    "Desktop is never affected.")
            .define("disable_entity_acceleration", false);

    /** 手机环境下是否强制关闭 AR 的物品/世界内物品加速。默认 false，用于性能 A/B。 */
    public static final ModConfigSpec.BooleanValue DISABLE_ITEM_ACCELERATION = BUILDER
            .comment("Mobile only. When true, hot-disables AR's item acceleration.",
                    "Use this to test whether AR item acceleration helps or hurts on a translation layer.",
                    "Desktop is never affected.")
            .define("disable_item_acceleration", false);

    /** 是否开启本模组自己的诊断行为（例如每组合批后 glGetError、统计日志）。默认 false。 */
    public static final ModConfigSpec.BooleanValue DEBUG_DIAGNOSTICS = BUILDER
            .comment("Enable ar-force-mobile's own diagnostic behavior.",
                    "This adds overhead and is intended for debugging only.",
                    "Default false.")
            .define("debug_diagnostics", false);

    /** 手机环境下是否启用实体同步屏障修复（解决 MobileGlues 上实体/方块闪烁）。默认 true。 */
    public static final ModConfigSpec.BooleanValue ENTITY_SYNC_FIX = BUILDER
            .comment("Mobile only. When true, inserts full GL memory barriers before accelerated draws",
                    "to fix flickering on MobileGlues (Adreno/Mali) when entity acceleration is on.",
                    "If barriers fail, auto falls back to disabling entity acceleration.",
                    "Desktop is never affected.")
            .define("entity_sync_fix", true);

    /**
     * 两种映射都不可用时开启 AR（tier-2 客户端内存 + 规范 orphan 上传，默认 false）。
     *
     * 1.3.16 起默认 false（原本 1.3.7～1.3.15 默认 true）：
     * - 在映射全死的入门设备（实测 Adreno 610 + MobileGlues 2.0.0）上，tier-2 每帧
     *   orphan 上传比不开 AR 还慢（实验四 10fps vs 实验三 AR 自动关闭的 20-30fps）；
     * - 且该设备上 BASEVERTEX 绘制路径（glDrawElementsBaseVertex）会 SIGSEGV
     *   （libmobileglues.so → libGLESv2_adreno.so → memcpy(NULL)）。
     * 两个映射探测都失败的设备现在默认回到原版渲染（等价实验三行为），日志固定为
     * "[ARForceMobile] AR disabled: no buffer mapping mode available (client_buffer_fallback=false)"。
     * 需要在老设备上继续实验 tier-2 时手动置 true（并建议同时确认
     * draw_method/safe_draw_method_on_client_buffer，避免踩 BASEVERTEX 崩溃路径）。
     * tier-1（普通映射）设备仍自动优先于本档。可用 -Darforcemobile.mapMode=client
     * 在健康设备上复现该路径。个别缓冲运行期映射失败时客户端内存仍无条件兜底。
     *
     * 1.3.24（B2 门禁/执行层解耦）：本键**不再单独放行 AR**。两探针都失败时门禁默认
     * 关闭 AR（原版渲染）；要跑 tier-2 必须**显式** -Darforcemobile.mapMode=client
     * （或 simulate=tier2* / 610-tier2* preset）。原因是旧行为会在门禁层预测性置位
     * 绘制方法安全闸，运行期真实映射可用时不会进客户端内存 -> INDIRECT + 未开 SSBO fix
     * -> MG 上 culling compute 编译失败 -> 启动崩（H-probe B2/R1）。
     * 保留本键是为了不破坏既有 toml 与配置结构；它仍会出现在 config 文件里。
     */
    public static final ModConfigSpec.BooleanValue CLIENT_BUFFER_FALLBACK = BUILDER
            .comment("Mobile only. Enable AR even when BOTH persistent and plain buffer mapping fail,",
                    "running AR from client memory with per-frame orphan uploads (glNamedBufferData).",
                    "Default false: on devices where every mapping mode is dead this tier is slower",
                    "than plain vanilla rendering, and its BASEVERTEX draw path crashes on",
                    "Adreno + MobileGlues 2.x. Such devices now fall back to vanilla rendering.",
                    "Since 1.3.24 this key does NOT enable AR by itself; tier-2 now requires an explicit",
                    "-Darforcemobile.mapMode=client (or a tier2*/610-tier2* simulate preset). Kept so that",
                    "existing config files stay valid.",
                    "Tier-1 (plain mapping) devices are preferred automatically before this tier.",
                    "Test the path on healthy devices with -Darforcemobile.mapMode=client.",
                    "Desktop is never affected.")
            .define("client_buffer_fallback", false);

    /**
     * Create 结构实体强制走原版渲染管线（ContraptionFilterMixin）。默认 true。
     *
     * 1.3.10 独有、1.3.11~1.3.16 主线丢失的能力，1.3.17 原样移植回来：命中 AR 的
     * FilterFeature.testEntity 时直接返回 false（= 该实体不加速），让 Create 的
     * contraption 实体落到原版渲染管线，交给 CreateBetterFPS（backend=off）的
     * Sodium fast path，而不是 AR 的 accelerated buffer source。
     * 只影响 Create contraption 实体；桌面环境不受影响。
     */
    public static final ModConfigSpec.BooleanValue CONTRAPTION_VANILLA_PIPELINE = BUILDER
            .comment("Mobile only. Force Create contraption entities onto the vanilla render pipeline,",
                    "letting the backend=off renderer use CreateBetterFPS's Sodium fast path instead of",
                    "AR's accelerated buffer source. Only affects Create contraption entities.",
                    "Desktop is never affected.")
            .define("contraption_vanilla_pipeline", true);

    // ==================== Flywheel 优化（手机环境，需安装 Flywheel 1.0.x 才会生效） ====================

    /**
     * 光照 section 滞回（ContraptionVisualLightHysteresisMixin）。
     *
     * Create 的 ContraptionVisual.checkAndUpdateLightSections 每帧用实体 AABB 求
     * min/max section，越界才整盒重建并通知 listener。转动结构的 AABB 角落随角度
     * 连续变化、频繁跨 section 边界，导致每帧重建 + LightStorage 监听风暴。
     * 开启后缓存上次提交的外扩 1 section 的范围，真实范围仍在其中时直接跳过，
     * 越出才以新范围 ±1 重建（提交超集，光照数值不变）。
     * 另保留 SectionTracker 等值去重兜底（对非 Create 的 visual 可能命中）。
     * 注意：Create 的 set 与 min/max 一一对应，等值去重对其是死代码——真机实测
     * 0 命中后才改为滞回方案。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_LIGHT_DEDUP = BUILDER
            .comment("Mobile only. Hysteresis for Create contraption light sections: cache an expanded",
                    "section range and skip rebuilds while the true range stays inside it, instead of",
                    "rebuilding + notifying listeners on every section-boundary crossing while rotating.",
                    "Desktop is never affected.")
            .define("flywheel_light_dedup", true);

    /**
     * transformed.vert 法线补丁。
     *
     * Flywheel 的 instance/transformed.vert 对每个顶点执行
     * mat3(transpose(inverse(i.pose)))——mat4 求逆是逐顶点纯 GPU ALU 开销，随结构
     * 顶点数线性放大，在 Adreno 610 这类入门 GPU 上是 Flywheel instancing 路径
     * 相比原版渲染慢的主要原因之一。Create 的结构实例都是刚体（无非均匀缩放），
     * mat3(i.pose) 数学等价。若有 mod 用 TRANSFORMED 实例做非均匀缩放导致光照
     * 异常，把此项关掉。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_SHADER_NORMAL_FIX = BUILDER
            .comment("Mobile only. Replace per-vertex mat3(transpose(inverse(pose))) with mat3(pose)",
                    "in Flywheel's transformed instance shader (exact for rigid transforms;",
                    "Create contraptions never scale). Disable if lighting looks wrong on",
                    "non-uniformly scaled TRANSFORMED instances from other mods.",
                    "Desktop is never affected.")
            .define("flywheel_shader_normal_fix", true);

    /**
     * TBO 重绑去重（带自动绑定）。
     *
     * Flywheel 的 TextureBuffer.bind 每个 draw call 都重新执行
     * glBindTexture(GL_TEXTURE_BUFFER)+glTexBuffer。glTexBuffer 的纹理附件跟随
     * 缓冲对象本身，数据未变时重复调用是纯冗余；在 MG 的 TBO 模拟路径
     * （ES<=3.1 驱动）上每次调用都会全量重拷整个实例缓冲。
     * 以 GlBuffer 上传次数为内容版本号，数据未变时跳过。
     *
     * 自动绑定（1.3.8）：仅在 AR 原生持久映射探测失败时启用。8Gen1 真机实测
     * ES 3.2 原生 glTexBuffer 是直通，去重零收益；而持久映射失败的老驱动与
     * TBO 模拟路径高度相关（都指向 ES<3.2/缺扩展）。现代设备自动零开销，
     * 老设备自动吃到收益，无需手动按机型开关。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_TBO_DEDUP = BUILDER
            .comment("Mobile only. Skip redundant glTexBuffer re-attachments in Flywheel when the",
                    "instance buffer content has not changed since the last attachment.",
                    "Auto-bound: only active when AR's native persistent-mapping probe fails",
                    "(old GLES drivers that emulate texture buffers). Devices with native",
                    "persistent mapping (modern ES 3.2) keep it off automatically.",
                    "Desktop is never affected.")
            .define("flywheel_tbo_dedup", true);

    /**
     * Flywheel 诊断统计（默认 false）。
     * 开启后每 5 秒打印：GlBuffer 上传次数/字节、光照 section 集重建次数、TBO 去重
     * 命中次数。用于在真机上定位 Flywheel 开销（配合开关逐项 A/B）。
     */
    public static final ModConfigSpec.BooleanValue DEBUG_FLYWHEEL_DIAGNOSTICS = BUILDER
            .comment("Mobile only. Log Flywheel buffer-upload volume and light-section churn every 5s",
                    "to locate the rotating-contraption overhead on real devices.",
                    "Desktop is never affected.")
            .define("debug_flywheel_diagnostics", false);

    /**
     * 实例脏标记抑制（InstanceDirtDedupMixin，主凶）。
     *
     * 反编译证实：TransformedInstance.setTransform 只写 pose 不调 setChanged，
     * 由调用方（Create 视觉层）每 tick 调 setChanged；AbstractInstance.setChanged
     * 是 final 的唯一脏标记 choke point。转动结构若走 embedding uniform 承载
     * 刚体运动，局部 pose 每帧重设的值与上次完全一致（或差值小到不可见），却
     * 触发全量 uploadSpan 风暴。
     * 开启后在 setChanged 处对比 pose（epsilon 1e-6/分量，视觉无损）+ color/
     * light/overlay 快照，全等才跳过；任一变化即放行并更新快照。异常时 fail-open
     *（放行原逻辑），桌面与开关关闭时零介入。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_INSTANCE_DIRT_DEDUP = BUILDER
            .comment("Mobile only. Skip redundant Flywheel instance dirty marks when pose (eps 1e-6)",
                    "and color/light/overlay are all unchanged since the last mark.",
                    "Kills the per-tick uploadSpan storm when local poses are static and rigid",
                    "motion is carried by the embedding uniform. Fail-open on error.",
                    "Desktop is never affected.")
            .define("flywheel_instance_dirt_dedup", true);

    /**
     * embedding 矩阵逐 draw 去重（EmbeddingDedupMixin）。
     *
     * 反编译证实：EmbeddedEnvironment.setupDraw 每个 draw 都执行
     * setMat4(_flw_modelMatrixUniform)+setMat3(_flw_normalMatrixUniform)，
     * 同一结构几十个 draw 传完全相同的值（poseComposed 按帧确定性合成，
     * 帧内逐 draw 不变）。开启后按 (program handle + 矩阵精确相等) 帧内缓存，
     * 相同则跳过两次 uniform 调用。uniform 是 per-program 状态，program 不同
     * 必放行，语义安全。
     * 1.3.12：8Gen1 真机 40 窗口命中≈0（(env,program) 粒度每帧唯一，设计失配），
     * 默认关闭留码；省每次比较开销。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_EMBEDDING_DEDUP = BUILDER
            .comment("Mobile only. Skip redundant per-draw embedding uniform uploads",
                    "(model/normal matrix) when program + matrices are unchanged since last draw.",
                    "Default false: real-device diagnostics showed near-zero hits (design mismatch).",
                    "Desktop is never affected.")
            .define("flywheel_embedding_dedup", false);

    /**
     * TBO 去重放开到原生路径（TextureBufferDedupMixin 门控）。
     *
     * 原自动绑定在 AR 持久映射探测成功（现代 ES 3.2）时关闭去重，假设原生
     * glTexBuffer 是直通零收益。MG 2.0 的 buffer_storage/DSA 行为已变，该假设
     * 需重验；且即使原生直通，去重仍省 JNI 与附件校验。开启后（默认）全驱动
     * 按内容版本号去重；关闭则回到旧自动行为。 master 开关仍是 flywheel_tbo_dedup。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_TBO_NATIVE_DEDUP = BUILDER
            .comment("Mobile only. Apply Flywheel TBO re-attach dedup on all drivers,",
                    "including ones where AR's persistent-mapping probe succeeds.",
                    "When false, falls back to the old auto behavior (dedup only when",
                    "persistent mapping fails). Master switch is still flywheel_tbo_dedup.",
                    "Desktop is never affected.")
            .define("flywheel_tbo_native_dedup", true);

    /**
     * S5：instance 数据 TBO 采样 → GLES 原生 instanced attribute（divisor）。
     *
     * R6 实证上传层全线结案（uploads=5×beginFrames 全是 5 个 UBO 的真数据，
     * instance 零变化），instancing vs off 的差距锁定在顶点层 TBO 采样税：
     * MG 2.0 的 TBO 是模拟层（重绑全量重拷实证），vertex shader 逐顶点
     * texelFetch 走翻译路径。本开关把 _flw_unpackInstance 的 texelFetch 序列
     * 替换为 divisor attribute 直读（GLES 3.0 核心，MG 原生支持）。
     * patch/绑定失败自动降级回 TBO（本会话，日志一次）。
     * 游戏内可切：/arforce s5 on|off，切换后重切一次 Flywheel backend 生效。
     * 命令只改内存，重启回本默认值。
     *
     * 1.3.14 默认改为 false（R7 教训：1.3.13 默认 on 时 patch 插入点 bug 使
     * rotating shader 编译失败，lazy 编译炸掉 InstancingPrograms 导致 instancing
     * 后端整机不可用，用户开局即被抛到 off 且无指引）。修复+失败感知齐备后，
     * 仍按"开关默认保守"纪律（§8.2-1）由用户在游戏内显式开启测试。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_TBO_DIVISOR = BUILDER
            .comment("Mobile only. Replace Flywheel's per-vertex TBO instance fetches with",
                    "native GLES instanced vertex attributes (vertexAttribDivisor).",
                    "MG's TBO emulation is the last remaining instancing-path tax after R6",
                    "cleared the upload layer. Auto-falls back to TBO on any failure.",
                    "Default false (R7: a patch bug broke shader compilation and killed the",
                    "instancing backend on session start). Toggle in-game with",
                    "/arforce s5 on|off, then re-switch the Flywheel backend to apply.",
                    "Desktop is never affected.")
            .define("flywheel_tbo_divisor", false);

    /**
     * UBO 内容去重（UniformBufferDedupMixin）。
     *
     * R6 实证：静止视角下 Frame/Player/Level/Fog/Options 五个 UBO 每帧全量
     * 重传（uploads=5×beginFrames），内容逐字节重复。开启后 bind 时比对
     * clientBuffer 与上次快照，全等压掉 needsUpload（glBindBufferRange 照做）。
     * 比对异常/首帧天然放行，零风险。游戏内 /arforce ubo on|off 即时生效。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_UBO_DEDUP = BUILDER
            .comment("Mobile only. Skip Flywheel UBO re-uploads whose content is byte-identical",
                    "to the last upload (R6: all 5 UBOs re-uploaded every frame even on a",
                    "static view). Toggle in-game with /arforce ubo on|off (immediate).",
                    "Desktop is never affected.")
            .define("flywheel_ubo_dedup", true);

    /**
     * I1（1.3.27）：Flywheel 光照档降档（默认 off = 绝不触碰 Flywheel 自己的配置）。
     *
     * 只允许 off | tri_linear（task-22 裁决）：TRI_LINEAR 把 embedded（contraption）片元的
     * 光照 LUT texelFetch 从 SMOOTH 的最坏 63 次降到 17 次（全实心早退 36）；FLAT（10 次）
     * 丢 AO 更多，**明确不做**。别再用"3.7×"这类理论比值去定档。
     * 启动期由 ARFLightState.applyAtStartup() **只改内存**（不写盘，重启回玩家值）；
     * 运行期 /arforce light 切换（改完必须 PipelineCompiler.deleteAll()）。
     */
    public static final ModConfigSpec.ConfigValue<String> FLYWHEEL_LIGHT_SMOOTHNESS = BUILDER
            .comment("Mobile only. Optional Flywheel lightSmoothness downgrade: off (default) | tri_linear.",
                    "tri_linear cuts embedded light-LUT texelFetch per fragment from 63 (SMOOTH worst case)",
                    "to 17; it drops contact AO. Only Create contraption/embedded visuals pay that tax.",
                    "Applied in memory only (never writes flywheel-client.toml). /arforce light switches it live.",
                    "Desktop is never affected.")
            .define("flywheel_light_smoothness", "off");

    /**
     * W2 OIT 旁路（1.3.27，默认 false）：把 ORDER_INDEPENDENT 材质换成 TRANSLUCENT 变体
     * （transparency=TRANSLUCENT + writeMask=COLOR），让 Flywheel 走原版同级的分层混合，
     * 而不是 OIT 的 3 次提交 + composite。只有视野里有 Flywheel 渲染的半透明物件
     * （contraption 玻璃/流体/玻璃管道里的液面）才有收益；不含半透明物件时 OIT 链本来就不跑。
     */
    public static final ModConfigSpec.BooleanValue FLYWHEEL_OIT_BYPASS = BUILDER
            .comment("Mobile only. Bypass Flywheel's OIT path: ORDER_INDEPENDENT materials render as",
                    "TRANSLUCENT (no depth write, vanilla-like layering) instead of the OIT passes.",
                    "Only affects Flywheel-instanced translucent objects. Default false: multi-layer",
                    "overlap may show ordering differences (known trade-off). /arforce oit toggles live.",
                    "Desktop is never affected.")
            .define("flywheel_oit_bypass", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * draw_method 的规范化取值：AUTO / BASEVERTEX / INDIRECT / DEFAULT。
     * 大小写不敏感，非法值与配置未加载（读取异常）都按 AUTO 处理。
     */
    public static String drawMethod() {
        // 模拟/调试覆盖（-Darforcemobile.drawMethod=... 或 simulate=<preset>），优先级最高。
        String simulated = ARSimArgs.drawMethod();
        if (simulated != null) {
            return simulated;
        }
        try {
            String value = DRAW_METHOD.get();
            if (value != null) {
                switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
                    case "BASEVERTEX":
                        return "BASEVERTEX";
                    case "INDIRECT":
                        return "INDIRECT";
                    case "DEFAULT":
                        return "DEFAULT";
                    default:
                        return "AUTO";
                }
            }
        } catch (Throwable t) {
            // 配置尚未加载：按 AUTO 处理（与默认值一致），不要把异常抛进绘制路径。
        }
        return "AUTO";
    }

    /**
     * flywheel_light_smoothness 规范化：只认 off / tri_linear（大小写不敏感）；
     * 其它值（含 flat / smooth / smooth_inner_face_corrected）一律按 off 处理并 WARN 一次。
     * 配置未加载（异常）同样按 off。
     */
    public static String flywheelLightSmoothness() {
        try {
            String value = FLYWHEEL_LIGHT_SMOOTHNESS.get();
            if (value == null) {
                return "off";
            }
            String mode = value.trim().toLowerCase(java.util.Locale.ROOT);
            if ("tri_linear".equals(mode)) {
                return "tri_linear";
            }
            if (!"off".equals(mode) && !"default".equals(mode) && !warnedLightMode) {
                warnedLightMode = true;
                LOGGER.warn("[ARForceMobile] flywheel_light_smoothness='{}' ignored (only off|tri_linear are supported; "
                        + "FLAT is deliberately not offered)", value);
            }
        } catch (Throwable t) {
            // 配置未加载：按 off
        }
        return "off";
    }

    private static boolean warnedLightMode;

    /** flywheel_oit_bypass 的读取（异常时按 false = 不干预）。 */
    public static boolean flywheelOitBypass() {
        try {
            return FLYWHEEL_OIT_BYPASS.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** safe_draw_method_on_client_buffer 的读取（异常时按默认 true = 优先避开崩溃路径）。 */
    public static boolean safeDrawMethodOnClientBuffer() {
        // 模拟/调试覆盖（-Darforcemobile.safeDrawMethodOnClientBuffer=true|false）。
        Boolean override = ARSimArgs.safeDrawMethodOnClientBuffer();
        if (override != null) {
            return override;
        }
        try {
            return SAFE_DRAW_METHOD_ON_CLIENT_BUFFER.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** mobile_auto_enable 的读取：-Darforcemobile.mobileAutoEnable 优先（模拟/调试用）。 */
    public static boolean mobileAutoEnable() {
        Boolean override = ARSimArgs.mobileAutoEnable();
        if (override != null) {
            return override;
        }
        try {
            return MOBILE_AUTO_ENABLE.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** client_buffer_fallback 的读取：-Darforcemobile.clientBufferFallback 优先（模拟/调试用）。 */
    public static boolean clientBufferFallback() {
        Boolean override = ARSimArgs.clientBufferFallback();
        if (override != null) {
            return override;
        }
        try {
            return CLIENT_BUFFER_FALLBACK.get();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- P2（task-38）：四个加速开关的运行期覆盖读取（/arforce gui|text|modernui|translucent）----
    // 语义：ARFAccelSwitches 的覆盖优先；未覆盖(null) 回落 toml；异常按各键默认值。

    /** disable_gui_acceleration 的读取（运行期覆盖优先）。 */
    public static boolean disableGuiAcceleration() {
        Boolean override = ARFAccelSwitches.guiDisabled();
        if (override != null) {
            return override;
        }
        try {
            return DISABLE_GUI_ACCELERATION.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** disable_text_acceleration 的读取（运行期覆盖优先）。 */
    public static boolean disableTextAcceleration() {
        Boolean override = ARFAccelSwitches.textDisabled();
        if (override != null) {
            return override;
        }
        try {
            return DISABLE_TEXT_ACCELERATION.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** disable_modernui_acceleration 的读取（运行期覆盖优先）。 */
    public static boolean disableModernuiAcceleration() {
        Boolean override = ARFAccelSwitches.modernuiDisabled();
        if (override != null) {
            return override;
        }
        try {
            return DISABLE_MODERNUI_ACCELERATION.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** disable_translucent_acceleration 的读取（运行期覆盖优先；toml 默认 true）。 */
    public static boolean disableTranslucentAcceleration() {
        Boolean override = ARFAccelSwitches.translucentDisabled();
        if (override != null) {
            return override;
        }
        try {
            return DISABLE_TRANSLUCENT_ACCELERATION.get();
        } catch (Throwable t) {
            return true;
        }
    }

    private ARForceConfig() {
    }
}
