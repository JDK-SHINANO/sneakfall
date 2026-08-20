# SneakFall（轻功）编译与验证记录

> 编译与验证过程记录。最终状态：**BUILD SUCCESSFUL**。

## 1. 时间线

| 时刻 | 事件 |
|------|------|
| 20:54 | 骨架编译通过（stub jar） |
| 21:18-21:20 | 完成 4 个 Java 文件的实现 |
| 21:20-21:28 | 静态审查 + import 修复；build 因审批拦截未执行 |
| 21:29 | 修复 logStages 时序 + 清理 mods.toml 占位注释 |
| 21:29 | **BUILD SUCCESSFUL in 4s** |

## 2. 编译错误与修复记录

| # | 错误 | 修复 |
|---|------|------|
| 1 | `import net.neoforged.fml.config.ModConfigSpec` 包名不存在（真身在 universal jar 的 `net.neoforged.neoforge.common.ModConfigSpec`） | 改为正确包名（javap 实测验证） |
| 2 | 主类构造器 `logStages()` 在 config 加载前执行，日志打印默认值而非用户配置值 | 移至 `FMLCommonSetupEvent` 监听（该事件在 LoadingConfigsEvent 之后） |
| 3 | mods.toml 残留"中文名占位"占位注释 | 清理（用户已定名「轻功」） |

## 3. jar 验证（三步，全部通过）

1. `unzip -l build/libs/sneakfall-0.1.0.jar`：10 个 class 齐全（SneakFall / SneakFallConfig + 5 内部类 / SneakFallTracker / FallDamageHandler + switch 合成类）+ mods.toml。jar 大小 15,856 B。
2. `unzip -p ... META-INF/neoforge.mods.toml`：`version="0.1.0"`、`displayName="轻功"` 中文无乱码、`neoforge [21.1.248,)`、`minecraft [1.21.1]` 均正确。
3. 产物路径：`./build/libs/sneakfall-0.1.0.jar`（项目内相对路径）

## 4. 静态逻辑复查清单（设计说明 §6）

| 检查项 | 结果 |
|--------|------|
| QTE 松键清零在 PlayerTickEvent.Pre（服务端）实现 | 通过 |
| 狂按 shift 无持续减免（判定要求落地瞬间 crouching 且从最近一次按下计时） | 通过 |
| 区间半开匹配 [min,max) + 空隙走 fallback 兜底 | 通过 |
| 仅 ServerPlayer 生效（创造/飞行走 PlayerFlyableFallEvent 不经此事件） | 通过 |
| DISTANCE → setDistance、PERCENT → setDamageMultiplier 正确作用于事件（伤害计算在事件后） | 通过 |
| 玩家退出清理 map 防泄漏 | 通过 |
| config 值动态读取、无静态初始化时序问题 | 通过（logStages 已迁移） |
| 配置文件自动生成验证 | runServer 验证进行中（proc_3ae37e4cc9f1） |

## 5. 遗留事项（交付说明）

- 机制的行为验证（实际跳崖手感、QTE 窗口体感）依赖用户游戏内实测——本模组无前置依赖，静态与构建层面检查已全部通过。
- 默认配置含一个示例阶段 `[3.0~20.0) QTE 0.4s DISTANCE 1.5`，用户首次生成 config 后可按注释自行调整。
- 若用户整合包 NeoForge 版本 ≠ 21.1.248，versionRange `[21.1.248,)` 要求不低于此版本。
