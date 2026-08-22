# SneakFall 轻功 / SneakFall

> **坠落时按住潜行，减免摔落伤害。**
> NeoForge 1.21.1 · Minecraft 1.21.1 · 开源模组（GPL-3.0）
>
> *Hold the sneak key while falling to reduce fall damage.*
> *NeoForge 1.21.1 · Minecraft 1.21.1 · Open-source mod (GPL-3.0)*

SneakFall（轻功）是一个 NeoForge 模组：玩家从高处坠落时按住潜行（Sneak），即可按配置规则减免摔落伤害。减免规则按坠落高度区间划分为多个"阶段"，每个阶段可独立配置触发模式与减免计算方式。

*SneakFall is a NeoForge mod: hold the sneak key while falling to reduce fall damage according to configurable rules. Reduction rules are divided into "stages" by fall-height ranges; each stage independently configures its trigger mode and reduction method.*

---

## 核心机制 / Core Mechanics

### 触发模式（mode）

| 模式 | 含义 |
| --- | --- |
| `SUSTAIN` | 落地瞬间按住潜行即减免（持续保持潜行状态即可） |
| `QTE` | 按下潜行后，必须在时间窗口内落地才减免；窗口外落地不减免 |

QTE 窗口以秒为单位配置（运行时按 1 秒 = 20 游戏刻换算），松开潜行立即归零重新计时。

### 减免计算方式（reductionMode）

| 模式 | 含义 |
| --- | --- |
| `DISTANCE` | 减免等效坠落格数：从实际坠落距离中扣除该格数后再计算伤害 |
| `PERCENT` | 按比例减免伤害：最终伤害 = 原始伤害 × (1 − 比例)，比例超 1 按 1 计 |

### 高度区间与保底

- 坠落高度按半开区间 `[min, max)` 匹配阶段，写法示例：`"3.0~20.0"`、`"10~"`（无上界）、`"~10"`（无下界）、`"all"`（全高度）。
- 每个阶段独立选择触发模式与减免方式，多个阶段按顺序匹配，命中第一个即生效。
- 未命中任何阶段（含区间空隙）时使用 `general` 分组中的**保底（fallback）**配置；默认保底不减免（`fallbackReductionValue = 0.0`），保证不意外免疫摔落伤害。
- 任一行配置解析失败只跳过该阶段，绝不导致服务器启动失败。

### 默认内置阶段

| 高度区间 | 模式 | QTE 窗口 | 减免方式 | 减免值 |
| --- | --- | --- | --- | --- |
| `3.0~20.0` | QTE | 0.4 s | DISTANCE | 1.5 格 |

### Trigger modes

| Mode | Meaning |
| --- | --- |
| `SUSTAIN` | Reduction applies if sneak is held at the moment of landing |
| `QTE` | After pressing sneak, you must land within the time window for reduction to apply |

QTE windows are configured in seconds (1 s = 20 game ticks at runtime); releasing sneak resets the timer.

### Reduction methods

| Mode | Meaning |
| --- | --- |
| `DISTANCE` | Reduces the equivalent fall distance by N blocks before damage is computed |
| `PERCENT` | Scales damage: final = original × (1 − ratio), ratio capped at 1 |

### Height ranges and fallback

- Height ranges match stages by half-open intervals `[min, max)`, e.g. `"3.0~20.0"`, `"10~"` (no upper bound), `"~10"` (no lower bound), `"all"` (any height).
- Stages match in order; the first match wins.
- If no stage matches (including gaps), the **fallback** config in the `general` group applies; the default fallback provides no reduction (`fallbackReductionValue = 0.0`) so fall damage is never unexpectedly negated.
- A single malformed config line only skips that stage; it never prevents server startup.

---

## 配置文件 / Configuration

首次启动时自动生成 `config/sneakfall-common.toml`（含全部中文注释，可直接阅读修改）。配置在事件触发时动态读取，游戏内无需重启即可通过 `/reload`（NeoForge）或重启生效。

*On first launch, `config/sneakfall-common.toml` is generated automatically (with full Chinese comments). Config is read dynamically per event, so changes take effect via `/reload` (NeoForge) or a restart — no need to restart the game manually.*

默认配置内容：

```toml
#SneakFall（潜行摔落减免）总体说明。
#按坠落高度区间定义减免规则：每个区间为一个阶段。
#每个阶段可独立选择触发模式（SUSTAIN / QTE）与减免计算方式（DISTANCE / PERCENT）。
#若坠落高度未命中任何阶段，则使用下方 general 分组中的保底（fallback）配置。
[general]
	#保底措施：坠落高度未落入任何 [stages] 阶段区间时使用（含区间空隙）。
	#默认不减免（0.0），保证不意外免疫。
	#Allowed Values: SUSTAIN, QTE
	fallbackMode = "SUSTAIN"
	# Default: 0.4
	# Range: 0.0 ~ 60.0
	fallbackQteWindow = 0.4
	#Allowed Values: DISTANCE, PERCENT
	fallbackReductionMode = "DISTANCE"
	# Default: 0.0
	# Range: 0.0 ~ 100.0
	fallbackReductionValue = 0.0

[stages]
	#摔落减免阶段列表（可删改）。
	#因底层配置库不支持嵌套对象数组（[[stages]] 数组表），改为 5 个等长平行列表：
	#第 i 个阶段由 heightRanges[i]、modes[i]、qteWindows[i]、reductionModes[i]、reductionValues[i] 组合而成。
	#示例（第 0 个阶段）：heightRanges=["3.0~20.0"]、modes=["QTE"]、qteWindows=[0.4]、
	#reductionModes=["DISTANCE"]、reductionValues=[1.5]，含义与原 [[stages]] 数组示例完全一致。
	#heightRanges 格式："min~max" 半开区间 [min,max)；"min~" 无上界；"~max" 无下界；"all" 全高度。
	#modes 取值：SUSTAIN / QTE（大小写不敏感，非法值跳过该阶段）。
	#qteWindows 仅 modes[i]=QTE 时生效，松开潜行立即归零重新计时。
	#reductionModes 取值：DISTANCE / PERCENT（大小写不敏感，非法值跳过该阶段）。
	#reductionValues：DISTANCE 模式为格数(0~100)；PERCENT 模式为比例(0~1，超 1 按 1 计)。
	#五个列表长度不一致时按最短长度截断；某一行解析失败时仅跳过该阶段。
	heightRanges = ["3.0~20.0"]
	modes = ["QTE"]
	qteWindows = [0.4]
	reductionModes = ["DISTANCE"]
	reductionValues = [1.5]
```

---

## 构建方法 / Building

要求：**JDK 21**（Gradle 8.x，NeoForge 21.1+）。

*Requires **JDK 21** (Gradle 8.x, NeoForge 21.1+).*

```bash
./gradlew build        # Linux / macOS
gradlew.bat build      # Windows
```

构建产物位于 `build/libs/`（`sneakfall-<版本>.jar`），放入 Minecraft 实例的 `mods/` 目录即可。

*The build artifact is in `build/libs/` (`sneakfall-<version>.jar`); drop it into your Minecraft instance's `mods/` directory.*

> 国内网络构建慢或超时时，可将 `gradle/wrapper/gradle-wrapper.properties` 中的
> `distributionUrl` 替换为镜像源（如腾讯云 `https://mirrors.cloud.tencent.com/gradle/...`）。
>
> *If Gradle downloads are slow or time out (common in mainland China), replace the `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` with a mirror (e.g. Tencent Cloud `https://mirrors.cloud.tencent.com/gradle/...`).*

---

## 文档目录（docs/）/ Documentation

开发文档，记录本模组从设计到交付的过程：

*Development documents recording the mod's journey from design to delivery:*

| 文档 | 内容 |
| --- | --- |
| `DESIGN_SPEC.md` | 总体设计说明：目标、机制设计、配置方案 |
| `SKELETON_REPORT.md` | 项目骨架搭建记录（构建脚本、mods.toml） |
| `MECHANICS_REPORT.md` | 摔落伤害机制与同类模组调研 |
| `API_REPORT.md` | 所用 NeoForge/事件 API 签名验证记录 |
| `BUILD_REPORT.md` | 编译与验证记录（JDK、Gradle、jar 输出） |
| `CONFIG_FIX_REPORT.md` | 配置序列化崩溃修复记录（平行列表方案、保底机制） |

---

## 目录结构 / Project Layout

```
sneakfall/
├── build.gradle            # 构建脚本（NeoForge 1.21.1）
├── gradle.properties       # Gradle / 模组属性（mod_id、版本）
├── settings.gradle         # 工程设置
├── gradlew / gradlew.bat   # Gradle Wrapper 启动脚本
├── gradle/wrapper/         # Wrapper（gradle-wrapper.jar + properties）
├── src/
│   ├── main/java/me/shinano/sneakfall/
│   │   ├── SneakFall.java           # 模组入口与事件注册
│   │   ├── SneakFallConfig.java     # 配置定义 / 阶段解析 / 保底
│   │   ├── SneakFallTracker.java    # 坠落距离与潜行状态追踪
│   │   └── FallDamageHandler.java   # 摔落伤害减免处理
│   └── main/resources/META-INF/
│       └── neoforge.mods.toml       # 模组元数据（license: GPL-3.0）
├── docs/                   # 开发文档
├── README.md
└── .gitignore
```

---

## 许可 / License

本模组按 **GPL-3.0** 开源协议发布，许可证全文见仓库根目录 `LICENSE` 文件。

*This mod is released under **GPL-3.0**. See the `LICENSE` file in the repository root.*
