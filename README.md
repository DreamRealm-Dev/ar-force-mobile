# AR Force Mobile

> **移动端着色器翻译层（MobileGLues / gl4es / LTW）专用的 Accelerated Rendering 兼容模组**
> Minecraft 1.21.1 · NeoForge 21.1+ · **仅客户端**

## 这是什么

`Accelerated Rendering`（下称 AR）在桌面 GPU 上表现很好，但它**显式拒绝在着色器翻译层下工作**：
`AvailabilityUtils.isAvailable()` 会读取 `glGetString(GL_RENDERER)`，只要字符串里含
`MobileGlues` / `gl4es` / `LTW` 就直接判定功能不可用。结果是在手机上（例如天玑 6100+ / Mali-G57），
AR 要么完全不生效，要么因为退到 `INDIRECT` / 原子计数器路径而**比不开还慢**。

AR Force Mobile 做的事：

1. **绕过 AR 对翻译层的黑名单**，让它在移动端真正跑起来；
2. **强制 `BASEVERTEX` 绘制方法 + SSBO 原子重写**（在本环境实测：`INDIRECT` 结构性慢，
   `MultiDraw` 方向不成立）；
3. **同 RenderType 合批**——自适应，收益不足时自动熄火；
4. 移动端默认关闭 `force_translucent_acceleration` 与 `debug_context`；
5. **桌面端完全不介入**：自动检测环境，非移动端不改动任何行为。

实测参考（8Gen1，单次跑分，方差约 ±10%，仅作方向参考）：
`BASEVERTEX + 合批` 相对关闭 AR，总分从 549.7 提升到 640.9。

## 环境要求

| 项 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.0 或更高 |
| 安装端 | **仅客户端**（`side = CLIENT`） |
| Accelerated Rendering | 可选依赖；未安装时模组静默跳过 |
| 渲染翻译层 | MobileGLues 2.x / gl4es / LTW（非移动环境不会启用任何开关） |

## 安装

1. 把 `ar-force-mobile-<版本>.jar` 放进 `.minecraft/mods/`；
2. 同时安装 Sodium / Embeddium 与 Accelerated Rendering；
3. 启动器需为支持翻译层的环境（PojavLauncher / MobileGlues / FCL 等）。

## 命令

进入世界后可用：

```
/arforce status                            # 总览
/arforce s5 on|off                         # BASEVERTEX 合批（divisor 路径）开关
/arforce ubo on|off                        # UBO 去重
/arforce light tri_linear|off              # 光照采样模式
/arforce oit on|off                        # OIT 旁路
/arforce ifleak status|fix on|off|reset    # AR × ImmediatelyFast 泄漏修复
/arforce gui on|off|status
/arforce text on|off|status
/arforce modernui on|off|status
/arforce translucent on|off|status
```

> 在非移动环境执行任何 `/arforce` 子命令都会提示 `mobile-only`。

## 配置

客户端配置位于 `config/arforcemobile-client.toml`。诊断输出默认关闭
（`debug_flywheel_diagnostics=false`），排障时再打开。

## 从源码构建

需要 JDK 21，构建使用 NeoForge ModDevGradle 2.0.134。

```bash
./gradlew build
```

**编译期依赖（第三方，不随本仓库分发）**：以下三个 jar 需自行放入 `./libs/`，否则编译会失败。

| 文件 | 用途 |
|---|---|
| `acceleratedrendering-1.0.14-1.21.1-alpha.jar` | AR 本体；合批层引用其 `BaseVertex` 相关类 |
| `flywheel-neoforge-1.21.1-1.0.6.jar` | 编译期引用 `SectionCollector` 接口；运行时由 Create 6.0.10 的 JarJar 内嵌提供 |
| `sodium-neoforge-0.8.13-dev-only.jar` | 编译期引用 `SodiumWorldRenderer` 的精确签名；运行时可选 |

三者都是 `compileOnly`，运行时不会被打进本模组的 jar。

## 兼容性

- Sodium / Embeddium、ImmediatelyFast、Flywheel / Create（含 contraption 渲染）、Iris、Curios、
  Sophisticated、ModernUI、FTB 等：模组内已带对应 mixin 并声明 `required:false`，
  目标模组缺失时静默跳过。
- 桌面端：不做任何修改。

## 已知边界

- 未采用 `INDIRECT` / `MultiDraw` 路线：在本环境（Mali-G57 + MobileGLues）结构性偏慢。
- S2（视锥 / 距离门）与 S3a（烘焙期体素剔除）目前是设计稿，**尚未实现**。
- 性能数据为单次采样，方差约 ±10%，大小关系需交错复测才能定论。

## 许可

MIT，见 [LICENSE](LICENSE)。
