# SKELETON_REPORT — SneakFall NeoForge 1.21.1 模组骨架

- 执行时间：2026-08-20 20:52 ~ 20:53 (+0800)
- 依据：设计说明 §5（构建规格）
- 参考树：另一个已建成的 NeoForge 项目（本模组构建体系的参照对象）
- 目标树：本仓库根目录

## 1. 已建文件清单

| 文件 | 来源/说明 |
|------|-----------|
| `gradle/wrapper/gradle-wrapper.jar` | 复制自参考项目，md5 `365e8981fbb8626c5235f955b3b92f0f` 与源一致 |
| `gradle/wrapper/gradle-wrapper.properties` | 原样复制（Gradle 9.2.1，distributionUrl 未改），md5 `fa69c27fa1dc5370bebb0147c8ded0f1` 与源一致 |
| `gradlew` | 复制自参考项目，已 `chmod +x` |
| `gradlew.bat` | 复制自参考项目 |
| `settings.gradle` | pluginManagement 同参考项目（NeoForged maven + gradlePluginPortal），`rootProject.name = 'sneakfall'` |
| `gradle.properties` | 按 §5 逐字（ASCII only）：`minecraft_version=1.21.1`、`neoforge_version=21.1.248`、`moddev_plugin_version=2.0.42-beta`、`mod_version=0.1.0`、`maven_group=me.shinano`、`archives_base_name=sneakfall` 等 |
| `build.gradle` | 精简版：moddev 2.0.42-beta + maven-publish；neoForge.version；implementation neoforge；processResources 展开 `${version}`；Java 21（options.release + toolchain 回退，同参考项目的 java 块）。**已删**：jarjar、lwjgl natives 平铺、Sodium/Iris/Lithium、Modrinth/CaffeineMC 仓库块、accessTransformers、client run |
| `src/main/resources/META-INF/neoforge.mods.toml` | 按 §5 模板（UTF-8，含中文 displayName「轻功」与描述；依赖行用 NeoForge 惯用 `;` 分隔符，nightconfig 解析器可接受） |
| `src/main/java/me/shinano/sneakfall/SneakFall.java` | 最小主类：`@Mod(SneakFall.MODID)`，构造器收 `IEventBus` + `ModContainer`，LOGGER 打印问候与版本信息；业务逻辑留待后续实现 |

## 2. 首次 build

- 启动命令：`./gradlew build --no-daemon --console=plain`
- 启动时间：2026-08-20 20:53:30 (+0800)
- 后台 session_id：`proc_cc18f251069b`（pid 88507，notify_on_complete=true）
- 产物预期：`build/libs/sneakfall-0.1.0.jar`

### build 结果

**BUILD SUCCESSFUL in 40s**（2026-08-20 20:54 完成，exit_code 0）

- 首次构建走本地缓存：transformSources 3.45s → recompile（5364 个 NeoForge 源码）23.00s → 总计 31.32s。
- `compileJava` / `processResources` / `jar` / `build` 全部成功；`jarJar` NO-SOURCE（精简后无内嵌库，符合预期）。
- 产物：`build/libs/sneakfall-0.1.0.jar`（2020 字节）。
- jar 验证：`me/shinano/sneakfall/SneakFall.class` 存在；`META-INF/neoforge.mods.toml` 中 `${version}` 已展开为 `0.1.0`，中文 displayName「轻功」与描述无乱码，neoforge/minecraft 依赖 versionRange 正确。
- 唯一告警：Gradle 9.2.1 提示若干 deprecated 特性（来自插件自身），不影响构建。

## 3. 已知问题 / 备注

1. 内置 TOML 校验器会拒绝 §5 模板中的 `;` 分隔依赖行（NeoForge 惯用写法，非标准 TOML 1.0）。已改用 heredoc 按 §5 原文写入，`file` 确认 UTF-8。
2. 其余文件无写入问题；wrapper 文件 md5 校验通过。
3. 未改动 distributionUrl（校园网阻断 services.gradle.org，但本机 ~/.gradle 缓存已热，wrapper 命中本地缓存）。
