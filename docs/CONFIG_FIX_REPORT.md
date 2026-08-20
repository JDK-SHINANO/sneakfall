# SneakFall config 序列化崩溃修复记录

**日期**：2026-08-20 21:45
**项目**：sneakfall 仓库（NeoForge 21.1.248，Minecraft 1.21.1，包 `me.shinano.sneakfall`）

## 1. 崩溃现象

服务器启动时 mod 构造器执行成功（日志出现「[SneakFall] 轻功已就绪」），随后 config 首次写盘崩溃：

```
com.electronwill.nightconfig.core.io.WritingException: Unsupported value type: class me.shinano.sneakfall.SneakFallConfig$StageSpec
```

崩溃发生在 `run/logs/latest.log` 21:39:10 附近（crash-reports/crash-2026-08-20_21.39.10-fml.txt），由 night-config 3.8.3 的 TOML 写入器抛出。

## 2. 源码分析结论（为什么不能保留 [[stages]] 数组表）

阅读 `/tmp/cfgspec/net/neoforged/neoforge/common/ModConfigSpec.java`（NeoForge 21.1.248）：

1. `defineList` 的全部重载最终汇入 `defineList(List<String> path, Supplier<List>, Supplier<T> newElementSupplier, Predicate<Object> elementValidator, Range sizeRange)`（约 614 行）。
2. 其 javadoc 声称："Directly supported are String, Boolean, Integer, Long and Double. Other classes will be saved using their string representation and will be read back from the config file as strings."——即**仅原生支持 5 种基础类型**；自定义类「以字符串表示保存」。
3. 但实际实现中，`ListValueSpec.correct()`（616-630 行）只做「按 elementValidator 过滤元素、空列表回退默认值」，**没有任何 toString 转换代码**。整个 ModConfigSpec.java 中 grep `toString` 仅有一处无关命中（Range 的 toString）。javadoc 的承诺在本版本未实现。
4. `ConfigValue.getRaw()` 直接 `config.getOrElse(path, defaultSupplier)`，`ModConfig.save()` 直接把内存对象交给 night-config 写入器 → 自定义对象（StageSpec）抛 WritingException。
5. **结论：NeoForge 21.1.248 的 defineList 不存在「自定义对象作列表元素」的官方正确写法**（无 childConfig / 嵌套 spec 支持）。`StageSpec` 中 push("stage")/pop() 定义的值并未成为列表路径下的子配置，StageSpec 对象本身就是一个无法序列化的普通 POJO。
6. 备选方案中**方案F（单字符串行）**可实现但可读性差、易错；**方案C（平行列表）**结构清晰、每列仍是标准 TOML 标量，用户体验最优 → **选用方案C**。

### 方案C 的一处必要变通（与原设计方案的差异及理由）

原设计方案要求 `modes=List<Mode>`、`reductionModes=List<ReductionMode>`。**实际实现改为 `List<String>`**（TOML 中内容完全一致，仍写 `"QTE"`/`"DISTANCE"`），理由：

- 官方 javadoc 明确仅直接支持 String/Boolean/Integer/Long/Double 五种元素类型，枚举属于「Other classes」；
- 本机 Gradle 缓存中无 night-config jar 可实证其 enum 序列化分支（缓存由 daemon 侧持有），web 检索也无结果；用 String 列可将序列化风险降为零——String 是官方原生支持类型，写盘/回读均绝对安全；
- 回读语义更稳：TOML 回读的元素本来就是 String，`Enum.valueOf` 在 matchStage 时集中解析（大小写不敏感），非法值仅跳过该阶段并告警，绝不影响启动；
- 用户编辑 config 的体验与 `List<Mode>` 完全一致（文件中都是枚举名字符串）。

数值列（qteWindows/reductionValues）按 `List<Double>` + 校验器 `obj -> obj instanceof Number`（TOML 浮点回读为 Double，用户手改整数回读为 Integer，Number 校验可兼容，消费时统一 doubleValue()）。

## 3. 改动摘要（仅动 SneakFallConfig.java 一个文件）

**文件**：`src/main/java/me/shinano/sneakfall/SneakFallConfig.java`（全部中文注释保留并扩充）

1. **删除** `StageSpec` 配置类（含 `new StageSpec(BUILDER)`、`STAGES = BUILDER.defineList("stages", ..., obj -> obj instanceof StageSpec)` 等全部旧定义）。
2. **新增 5 个平行 ConfigValue 列表**（push("stages") 分组内）：
   - `STAGE_HEIGHT_RANGES` = defineList("heightRanges", List<String>)
   - `STAGE_MODES` = defineList("modes", List<String>)
   - `STAGE_QTE_WINDOWS` = defineList("qteWindows", List<Double>)
   - `STAGE_REDUCTION_MODES` = defineList("reductionModes", List<String>)
   - `STAGE_REDUCTION_VALUES` = defineList("reductionValues", List<Double>)
   每个都有 newElementSupplier（"3.0~20.0"/"QTE"/0.4/"DISTANCE"/1.5），供配置 UI 的「添加」按钮使用。
3. **StageSpec 改造为纯解析容器**：私有 record `StageDefaults`，`defaultStages()` 返回与旧默认完全一致的单示例阶段（"3.0~20.0"/QTE/0.4/DISTANCE/1.5）。
4. **matchStage 重写**：按 index 同时遍历 5 个列表组装 `RuntimeStage`；长度不一致按最短截断并 `LOGGER.warn`；每行任一列解析失败（heightRange 非法、枚举名非法、数值非法）只跳过该 index 并告警；全部未命中返回 `buildFallback()`。
5. **新增 3 个防御性解析助手**：`parseMode` / `parseReductionMode`（`Enum.valueOf`，大小写不敏感）、`parseDouble`（兼容 Number 与 String）、`minLength`（5 列表最短长度）。
6. **logStages 重写**：输出格式与旧版一致（「共加载 N 个摔落减免阶段」+ 每阶段一行含解析后的 [min ~ max) 区间）。
7. **完全未动**：`Mode`/`ReductionMode` 枚举、`RuntimeStage`/`HeightRange` record、`parseHeightRange`、`buildFallback`、`normalize`、`general` 保底分组，以及对外 API（`SPEC`、`matchStage`、`logStages`、`RuntimeStage`）签名——`SneakFall.java` 与 `FallDamageHandler.java` 零改动。

## 4. 编译与部署

- `./gradlew build`：**BUILD SUCCESSFUL in 4s**（期间修过一次编译错误：StageDefaults 最初写成普通 final class，字段访问误写为方法调用，改为 record 后通过）。
- `cp build/libs/sneakfall-0.1.0.jar run/mods/`：已覆盖旧 jar（21:45，17651 字节）。
- 清理了上次崩溃遗留的半写文件 `run/config/sneakfall-common.new.tmp.toml`。

## 5. 运行验证（全部通过）

命令：`timeout 240 ./gradlew runServer --console=plain > /tmp/sf5.log 2>&1`（exit=124 即 timeout 按预期杀掉了长驻服务器，日志显示 `Done (0.954s)! For help, type "help"` —— 服务器完整启动成功）。

| 检查项 | 结果 |
|---|---|
| (a) 出现「[SneakFall] 轻功已就绪」 | 通过：/tmp/sf5.log 与 run/logs/latest.log 各 1 处 |
| (b) 无 WritingException / Unsupported value type | 通过：两日志 grep 计数均为 0，也无 StageSpec 字样 |
| (c) run/config/sneakfall-common.toml 生成 | 通过：21:46 新生成（1961 字节），内容见下 |
| 附加：阶段加载日志 | 通过：`[SneakFall] 共加载 1 个摔落减免阶段` + `阶段 0：heightRanges='3.0~20.0'（[3.0 ~ 20.0)），modes=QTE，qteWindows=0.4，reductionModes=DISTANCE，reductionValues=1.5` |
| 附加：无配置校正告警 | 通过：日志无 "Incorrect key" / correction 告警，config 未被反复回写 |

生成的 `run/config/sneakfall-common.toml` 关键内容（中文注释完整、结构清晰）：

```toml
#SneakFall（潜行摔落减免）总体说明。
...
[general]
	#保底措施：坠落高度未落入任何 [stages] 阶段区间时使用（含区间空隙）。
	#默认不减免（0.0），保证不意外免疫。
	#Allowed Values: SUSTAIN, QTE
	fallbackMode = "SUSTAIN"
	fallbackQteWindow = 0.4
	fallbackReductionMode = "DISTANCE"
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

## 6. 结论与遗留事项

- **崩溃根因**：NeoForge 21.1.248 的 `defineList` 不实现 javadoc 声称的自定义元素 toString 序列化，自定义对象（StageSpec）作列表元素必然 WritingException。
- **修复**：方案C 平行列表 + 枚举列用 String（零序列化风险、行为等价）。
- **验证**：build + 覆盖 jar + runServer 全链路跑通，config 首次写盘成功，三项验收标准全部通过，无遗留步骤。
- **潜在改进（可选，非必须）**：若未来 NeoForge 提供嵌套 spec 的 list 支持，可考虑恢复 `[[stages]]` 数组表结构；当前平行列表方案已是官方类型体系内的最优解。
