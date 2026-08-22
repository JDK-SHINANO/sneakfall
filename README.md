[简体中文](README.zh-CN.md)

# SneakFall

> **Hold the sneak key while falling to reduce fall damage.**
> NeoForge 1.21.1 · Minecraft 1.21.1 · Open-source mod (GPL-3.0)

SneakFall is a NeoForge mod: hold the sneak key while falling to reduce fall damage according to configurable rules. Reduction rules are divided into "stages" by fall-height ranges; each stage independently configures its trigger mode and reduction method.

---

## Core Mechanics

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

### Default built-in stage

| Height range | Mode | QTE window | Reduction | Value |
| --- | --- | --- | --- | --- |
| `3.0~20.0` | QTE | 0.4 s | DISTANCE | 1.5 blocks |

---

## Configuration

On first launch, `config/sneakfall-common.toml` is generated automatically (with full Chinese comments). Config is read dynamically per event, so changes take effect via `/reload` (NeoForge) or a restart — no need to restart the game manually.

Default configuration:

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

## Building

Requires **JDK 21** (Gradle 8.x, NeoForge 21.1+).

```bash
./gradlew build        # Linux / macOS
gradlew.bat build      # Windows
```

The build artifact is in `build/libs/` (`sneakfall-<version>.jar`); drop it into your Minecraft instance's `mods/` directory.

> If Gradle downloads are slow or time out (common in mainland China), replace the `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` with a mirror (e.g. Tencent Cloud `https://mirrors.cloud.tencent.com/gradle/...`).

---

## Documentation (docs/)

Development documents recording the mod's journey from design to delivery:

| Document | Contents |
| --- | --- |
| `DESIGN_SPEC.md` | Overall design spec: goals, mechanics design, config scheme |
| `SKELETON_REPORT.md` | Project skeleton setup record (build script, mods.toml) |
| `MECHANICS_REPORT.md` | Fall damage mechanics and similar-mod research |
| `API_REPORT.md` | NeoForge/event API signatures used, verification record |
| `BUILD_REPORT.md` | Build and verification record (JDK, Gradle, jar output) |
| `CONFIG_FIX_REPORT.md` | Config serialization crash fix record (parallel-list scheme, fallback) |

---

## Project Layout

```
sneakfall/
├── build.gradle            # Build script (NeoForge 1.21.1)
├── gradle.properties       # Gradle / mod properties (mod_id, version)
├── settings.gradle         # Project settings
├── gradlew / gradlew.bat   # Gradle Wrapper launch scripts
├── gradle/wrapper/         # Wrapper (gradle-wrapper.jar + properties)
├── src/
│   ├── main/java/me/shinano/sneakfall/
│   │   ├── SneakFall.java           # Mod entry point and event registration
│   │   ├── SneakFallConfig.java     # Config definition / stage parsing / fallback
│   │   ├── SneakFallTracker.java    # Fall distance and sneak state tracking
│   │   └── FallDamageHandler.java   # Fall damage reduction handling
│   └── main/resources/META-INF/
│       └── neoforge.mods.toml       # Mod metadata (license: GPL-3.0)
├── docs/                   # Development documents
├── README.md
└── .gitignore
```

---

## License

This mod is released under **GPL-3.0**. See the `LICENSE` file in the repository root.
