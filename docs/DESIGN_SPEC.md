# SneakFall 模组设计说明（MC 1.21.1 / NeoForge）

> 本文件为项目总体设计说明，编码前应先阅读。
> 项目目录：本仓库根目录

## 1. 目标与环境

- Minecraft **1.21.1**，加载器 **NeoForge 21.1.248**（用户整合包实测版本）
- Java 21（本机已装 openjdk-21-jdk-headless）
- **零前置依赖、零 mixin、零 access transformer**——纯 NeoForge 事件 API 即可实现
- modId = `sneakfall`，maven_group = `me.shinano`，包名 `me.shinano.sneakfall`
- displayName 中文名待定（"轻功"或"平稳触地"，先占位"轻功"，发布前可改）
- 构建体系参照一个已建成的 NeoForge 项目（moddev 2.0.42-beta + Gradle 9.2.1，本机 Gradle 缓存已热，勿改版本号）

## 2. 玩法机制规格

**核心效果**：玩家坠落时按住潜行（Shift）落地，可获得摔落伤害减免。

### 2.1 两种判定模式（每个阶段可分别配置）

| 模式 | 逻辑 |
|------|------|
| `SUSTAIN`（持续） | 落地瞬间只要正在潜行，就减免 |
| `QTE` | 从**按下潜行那一刻**开始计时；落地瞬间若正在潜行**且**按下至落地耗时 ≤ QTE 窗口（秒），才减免 |

### 2.2 QTE 防漏洞规则（硬性要求，不可违背）

1. 按下潜行时记录起始时刻（服务端 tick）。
2. **松开潜行 → 立即清除起始记录（窗口归零）**。再次按下则重新计时。
3. 减免判定条件是"落地瞬间正在潜行"+"窗口未超时"同时成立。
   因为松开即清零，所以"狂按 shift 反复重置"的漏洞天然不存在：落地瞬间必须正按着 shift，且从最近一次按下起的时间 ≤ 窗口。
4. 玩家**在地面按下潜行后一直蹲着**走出悬崖：起始时刻在地面按下时就记录，窗口早已超时 → QTE 模式下不减免（这正是"及时按下"的操作感：必须起跳后/坠落中按）。SUSTAIN 模式不受影响（只要落地时蹲着就减）。
5. QTE 窗口单位是**秒**（配置文件写 0.4），运行时换算 tick：`windowTicks = ceil(seconds * 20)`。

### 2.3 坠落高度区间与保底

- 摔落伤害公式（1.21.1）：`damage = ceil((fallDistance - 3.0) * damageMultiplier)`，fallDistance 为坠落距离（格）。
- 每个阶段配置一个高度区间，**半开区间 [min, max)**，按事件触发的 fallDistance（减免前）匹配。
- 区间写法（字符串，配置见 §3）：
  - `"3.0~20.0"` → [3, 20)
  - `"5.0~"` → [5, +∞)
  - `"~10.0"` → (-∞, 10)（实际从 3 起才有伤害）
  - `"all"` → 全高度
- **保底措施**：fallDistance 未落入任何 stage 区间（含区间间有空隙、配置空、全部解析失败）时，启用 `[general] fallback*` 配置。fallback 默认减免 0（无减免、无意外免疫），默认 SUSTAIN 模式。
- 区间解析失败（格式错误、min > max、非数字）→ 该 stage 无效，跳过并在日志 WARN；全部无效则 fallback 兜底。

### 2.4 伤害减免语义（两种，按阶段配置）

| reductionMode | 语义 | 实现方式 |
|---------------|------|----------|
| `DISTANCE` | 减免等效坠落格数 | `event.setDistance(max(0, distance - value))` |
| `PERCENT` | 按比例减免伤害 | `event.setDamageMultiplier(multiplier * (1 - value))` |

- `value` 范围：DISTANCE 建议 0~10 格；PERCENT 0.0~1.0。
- 默认配置推荐 DISTANCE 1.5（4 格掉落从 1 伤害减到 0，低高度也有效——PERCENT 在小伤害时因 ceil 取整会被吞掉减免）。
- 减免通过 NeoForge `LivingFallEvent` 修改，伤害计算在事件之后进行，修改即生效。

## 3. 配置文件规格（config/sneakfall-common.toml）

```toml
[general]
	# 保底措施：坠落高度未落入任何 [[stages]] 区间时启用以下设置（默认不减免）
	fallbackMode = "SUSTAIN"      # SUSTAIN / QTE
	fallbackQteWindow = 0.4       # 秒，仅 fallbackMode = QTE 时生效
	fallbackReductionMode = "DISTANCE"  # DISTANCE / PERCENT
	fallbackReductionValue = 0.0  # 默认 0 = 不减免

[[stages]]
	# —— 阶段一（示例）：中高度坠落，QTE 判定 ——
	# 坠落高度区间（格）。"min~max" 半开区间；省略一侧 = 无界；"all" = 全高度
	heightRange = "3.0~20.0"
	# 判定模式：SUSTAIN = 落地瞬间按住潜行即减免；QTE = 按下潜行后窗口内落地才减免
	mode = "QTE"
	# QTE 窗口（秒）。松开潜行立即归零；仅 mode = QTE 时生效
	qteWindow = 0.4
	# 减免方式与数值：DISTANCE = 减免等效坠落格数；PERCENT = 伤害按比例减免（0.0~1.0）
	reductionMode = "DISTANCE"
	reductionValue = 1.5

[[stages]]
	# —— 阶段二（示例）：全高度，持续判定，减 30% ——
	heightRange = "all"
	mode = "SUSTAIN"
	qteWindow = 0.0
	reductionMode = "PERCENT"
	reductionValue = 0.3
```

- 配置类型 `ModConfig.Type.COMMON`（服务端主导，同步客户端）。
- ModConfigSpec 实现：`defineList("stages", ArrayList::new, () -> new StageSpec(BUILDER.push("stage")))`，每个 StageSpec 内部用 define 定义上述 5 个字段。mode/reductionMode 用 `defineEnum`。
- `qteWindow` 在 SUSTAIN 模式下忽略。
- 配置注释必须中英对照、解释清楚字段含义（用户是使用者视角）。

## 4. 技术架构

```
src/main/java/me/shinano/sneakfall/
├── SneakFall.java          # @Mod 主类：构造器注册 config + bus.addListener
├── SneakFallConfig.java    # ModConfigSpec；GENERAL 兜底块 + List<StageSpec>
│                           # StageSpec 记录：heightRange(解析后 min/max)、mode、qteWindowTicks、reductionMode、reductionValue
│                           # 提供 matchStage(double fallDistance) -> StageSpec（无匹配返回 fallback StageSpec）
│                           # 提供 parseHeightRange(String) 静态纯函数（"all"/"a~b"/"a~"/"~b"，非法返回 null）
├── SneakFallTracker.java   # per-player 潜行追踪：ConcurrentHashMap<UUID, Long>（qte 起始 server tick）
│                           # 在 PlayerTickEvent.PRE：crouching=false→true 时记录当前 serverTickCount；!crouching 时 remove
│                           # 提供 isQteActive(ServerPlayer, windowTicks)（必须"记录存在"且未超时）
│                           # 玩家退出（PlayerEvent.PlayerLoggedOutEvent）时 remove 条目防泄漏
└── FallDamageHandler.java  # @SubscribeEvent LivingFallEvent：
                            #   仅 ServerPlayer；matchStage(event.getDistance())；
                            #   SUSTAIN → 判定 player.isCrouching()；QTE → 判定 tracker.isQteActive(...)；
                            #   通过后按 reductionMode 调 setDistance / setDamageMultiplier
```

- **事件注册**（NeoForge 1.21.1 惯用法，参照一个已建成的 NeoForge 项目）：
  `@Mod(SneakFall.MODID)` 主类构造器 `(IEventBus modBus, ModContainer container)`：
  `modBus.addListener(FallDamageHandler::onLivingFall)`（或 new 实例 :: 方法）；
  全局事件用 `NeoForge.EVENT_BUS.addListener(...)`（PlayerTickEvent / PlayerLoggedOutEvent 属游戏事件总线，也可用 @EventBusSubscriber）。
- LivingFallEvent 在服务端逻辑侧触发，所有逻辑集中在服务端，客户端无代码。
- 无需 mixin.json、无需 accesstransformer.cfg。

## 5. 构建规格

- `gradle/wrapper/gradle-wrapper.properties`：**原样复制**参考项目的（Gradle 9.2.1，本机缓存命中，不改 distributionUrl）。
- `gradlew`/`gradlew.bat`/`gradle-wrapper.jar`：从参考项目复制，`chmod +x gradlew`。
- `settings.gradle`：pluginManagement 复制，`rootProject.name = 'sneakfall'`。
- `gradle.properties`：
  ```
  org.gradle.jvmargs=-Xmx2G
  org.gradle.caching=true
  org.gradle.parallel=true
  org.gradle.daemon=false
  minecraft_version=1.21.1
  neoforge_version=21.1.248
  moddev_plugin_version=2.0.42-beta
  mod_version=0.1.0
  maven_group=me.shinano
  archives_base_name=sneakfall
  ```
- `build.gradle`：以参考项目为底**大幅精简**——删 jarjar/natives/Sodium/Iris/仓库块：
  ```groovy
  plugins { id 'net.neoforged.moddev' version '2.0.42-beta'; id 'maven-publish' }
  version = project.mod_version; group = project.maven_group
  base { archivesName = project.archives_base_name }
  neoForge { version = project.neoforge_version }
  dependencies { implementation "net.neoforged:neoforge:${project.neoforge_version}" }
  processResources {
      inputs.property 'version', project.version
      filesMatching('META-INF/neoforge.mods.toml') { expand 'version': project.version }
  }
  java { ... Java 21 ... }  // 同参考项目
  tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release = 21 }
  ```
- `src/main/resources/META-INF/neoforge.mods.toml`：
  ```
  modLoader="javafml"
  loaderVersion="[4,)"
  license="GPL-3.0"
  [[mods]]
  modId="sneakfall"
  version="${version}"
  displayName="轻功"            # 中文名占位，发布前确认
  description="坠落时按住潜行减免摔落伤害（可配置阶段/判定模式/减免量）"
  authors="shinano"
  [[dependencies.sneakfall]]
  modId="neoforge"; type="required"; versionRange="[21.1.248,)"; ordering="NONE"; side="BOTH"
  [[dependencies.sneakfall]]
  modId="minecraft"; type="required"; versionRange="[1.21.1]"; ordering="NONE"; side="BOTH"
  ```
- 编译：`./gradlew build`（后台跑，本机缓存热，预计几分钟）。产物 `build/libs/sneakfall-0.1.0.jar`。

## 6. 验证清单

1. `./gradlew build` BUILD SUCCESSFUL。
2. `unzip -l` jar：类文件齐全（me/shinano/sneakfall/*.class）。
3. `unzip -p jar META-INF/neoforge.mods.toml`：modId/version/versionRange 正确、displayName 中文无乱码。
4. 静态逻辑复查：
   - QTE 清零规则实现在 PlayerTickEvent.PRE（松开即 remove）；
   - 狂按 shift 无法获得持续减免（判定要求落地瞬间 crouching 且从最近一次按下计时）；
   - 区间匹配半开区间 [min, max)；"all" / 无界写法可解析；
   - 保底 fallback 生效路径（无匹配时返回 fallback StageSpec，默认 0 减免）；
   - PERCENT 模式 setDamageMultiplier 与 DISTANCE 模式 setDistance 均作用于事件（伤害计算在事件后）；
   - 仅 ServerPlayer 生效、实体非玩家直接 return；防 NPE（config 加载时序：事件触发时 config 已加载完毕，但兜底处理 null）。
5. （可选加分项）`./gradlew runServer` 生成默认 config 文件并检查 TOML 结构与注释质量。若网络受阻可跳过，交付时说明。

## 7. 任务分工

- 阶段 0（并行）：
  - API 签名调研 → `docs/API_REPORT.md`（javap 验证 LivingFallEvent / PlayerTickEvent / isCrouching / ModConfigSpec / 事件注册 API 精确签名）
  - 项目骨架 → 可编译工程骨架 + 首次 build 验证
  - 机制调研 → `docs/MECHANICS_REPORT.md`（摔落伤害公式、同类模组原理）
- 阶段 1：核心实现——按 §2/§3/§4 + API_REPORT 实现全部业务代码。
- 阶段 2：编译、修错、按 §6 验证。
- 阶段 3：复核 + 交付记录（jar 路径、config 示例、测试指引）。
