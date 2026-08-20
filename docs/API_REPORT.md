# API_REPORT — NeoForge 21.1.248 + Minecraft 1.21.1 API 签名验证记录

> 生成方式：javap 反汇编本机 Gradle 缓存工件 + 读取 NeoForge 补丁源码（moddev 生成的 sources jar）。
> 工件取自本机 Gradle 缓存与 moddev 生成的 sources jar。

---

## 1. LivingFallEvent（与设计说明的假设基本一致，但取消机制是接口不是注解）

**结论：可用。精确签名（javap -p -v 实测，字节码 class 版本 65 = Java 21）：**

```java
package net.neoforged.neoforge.event.entity.living;

public class LivingFallEvent extends LivingEvent implements ICancellableEvent {
    private float distance;          // ACC_PRIVATE
    private float damageMultiplier;  // ACC_PRIVATE

    public LivingFallEvent(LivingEntity entity, float distance, float damageMultiplier);
    public float getDistance();                    // ()F
    public void setDistance(float distance);       // (F)V
    public float getDamageMultiplier();            // ()F
    public void setDamageMultiplier(float damageMultiplier); // (F)V
}
```

- **父类链（实测 javap）**：`LivingFallEvent → LivingEvent → EntityEvent → Event`。
- **取消机制：没有 `@Cancelable` 注解**。`net.neoforged.bus.api.Cancelable` 注解类在 bus 8.0.2 中**已删除**（javap 报 `错误: 找不到类`）。21.x 起改为标记接口 `implements net.neoforged.bus.api.ICancellableEvent`，它提供 default 方法：
  ```java
  public interface ICancellableEvent {
      public default void setCanceled(boolean);
      public default boolean isCanceled();   // 即旧版 isCancelable() 对应物
  }
  ```
  因此代码里用 `event.isCanceled()` / `event.setCanceled(true)`，**没有 `isCancelable()` 方法**。
- 事件在 **`NeoForge.EVENT_BUS`（游戏总线）** 上触发（源码 javadoc 明文），构造点见 §4。

**代码示例：**

```java
@SubscribeEvent // 或用 NeoForge.EVENT_BUS.addListener(...)
public static void onLivingFall(LivingFallEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return; // 事件对所有 LivingEntity 触发，必须过滤
    // …… matchStage(event.getDistance()) ……
    event.setDistance(Math.max(0f, event.getDistance() - 1.5f));        // DISTANCE 减免
    event.setDamageMultiplier(event.getDamageMultiplier() * 0.7f);      // PERCENT 减免
}
```

**依据：**
- javap：`/tmp/apirep/javap_LivingFallEvent.txt`（universal jar 内 `net/neoforged/neoforge/event/entity/living/LivingFallEvent.class`，字段/方法如上）
- 源码：neoforge-21.1.248-sources.jar `LivingFallEvent.java` L30-54；`ICancellableEvent.java`（bus-8.0.2.jar）
- javap：`LivingEvent extends EntityEvent`、`EntityEvent extends Event`（sources L24 两处）

---

## 2. PlayerTickEvent / Pre / Post / Phase（Phase 枚举已不存在，须改用 Pre/Post 类）

**结论：可用，但设计说明 §4 里写的 `PlayerTickEvent.PRE` 必须改为 `PlayerTickEvent.Pre` 类；`Phase` 枚举在 21.1.248 已被移除。**

```java
package net.neoforged.neoforge.event.tick;

public abstract class PlayerTickEvent extends PlayerEvent {
    protected PlayerTickEvent(Player player);     // ACC_PROTECTED，抽象基类不可直接实例化

    public static class Pre extends PlayerTickEvent {   // NestMembers 证实
        public Pre(Player player);
    }
    public static class Post extends PlayerTickEvent {
        public Post(Player player);
    }
}
```

- `PlayerEvent` 继承链：`PlayerEvent → LivingEvent → EntityEvent → Event`，`PlayerEvent.getEntity()` 返回 **Player**（协变覆盖，javap 证实有 3 个桥接 getEntity）。
- **没有 `PlayerTickEvent$Phase`**（javap `错误: 找不到类`；源码文件 57 行内也无 Phase）。这是 1.20.5 起 NeoForge 的破坏性变更：旧 Forge 的 `Phase.START/END` 被 Pre/Post 两个内部类取代。
- 注意：事件在**服务端和客户端两边都触发**（每 tick、每玩家）。服务端玩家 tick 走 `ServerPlayer#doTick()`（由 `ServerGamePacketListenerImpl#tick()` 调用），客户端走 `Entity#tick()`。必须 `if (player.level().isClientSide()) return;` 只处理服务端（追踪逻辑全部在服务端，此检查必要）。
- 该事件属游戏事件，用 `NeoForge.EVENT_BUS` 或 `@EventBusSubscriber` 注册（与设计说明的判断一致）。

**代码示例（潜行追踪用 Pre，见 §5 tickCount）：**

```java
public static void onPlayerTick(PlayerTickEvent.Pre event) {
    Player p = event.getEntity();
    if (p.level().isClientSide() || !(p instanceof ServerPlayer sp)) return;
    // crouching 边沿检测：false→true 记录 sp.tickCount；true→false 清除
}
```

**依据：**
- javap：`/tmp/apirep/javap_PlayerTickEvent.txt`（`abstract class PlayerTickEvent extends PlayerEvent`、NestMembers: `$Post`、`$Pre`、InnerClasses: `public static Pre`、`public static Post`）
- javap：`/tmp/apirep/javap_PrePost.txt`（Pre/Post 均为 `public class ... extends PlayerTickEvent`，public 构造器 `(Player)`）
- 源码：neoforge sources `PlayerTickEvent.java` L27-57

---

## 3. ModConfigSpec.defineList / defineEnum / Builder.push/pop（设计说明的 3 参 defineList 写法需加第 4 个参数）

**结论：push/pop/defineEnum 的用法照抄可行；`defineList("stages", ArrayList::new, () -> new StageSpec(BUILDER.push("stage")))` 这种**三参形式不存在**——必须补第 4 个 `Predicate<Object> elementValidator` 参数，或用 `defineListAllowEmpty`。**

javap 实测 `ModConfigSpec$Builder` 中 defineList 的全部重载（节选关键者）：

```java
// 注意：不存在 defineList(String, Supplier<List>, Supplier<T>) —— 三参第三位是 Predicate，不是 Supplier
public <T> ConfigValue<List<? extends T>> defineList(
        String path, Supplier<List<? extends T>> defaultSupplier,
        Predicate<Object> elementValidator);                                    // 3 参：无新元素供应商
public <T> ConfigValue<List<? extends T>> defineList(
        String path, Supplier<List<? extends T>> defaultSupplier,
        Supplier<T> newElementSupplier, Predicate<Object> elementValidator);    // 4 参：此形式存在
public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(                 // 允许空列表的 4 参版本
        String path, Supplier<List<? extends T>> defaultSupplier,
        Supplier<T> newElementSupplier, Predicate<Object> elementValidator);
```

- `defineEnum`：`public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue)`（还有带 `EnumGetMethod` / `Collection` 的重载）。`EnumValue<V>.get()` 返回枚举值。
- `push/pop`：`public Builder push(String path)`、`public Builder push(List<String> path)`、`public Builder pop()`、`public Builder pop(int count)`。`push` 返回 Builder 本身，**可以嵌套调用**——`BUILDER.push("stage")` 的返回值就是 Builder，正是官方文档推荐的嵌套元素写法。
- `BUILDER` 必须是 `private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();`，最后 `SPEC = BUILDER.build();` 产出 `ModConfigSpec`（`implements net.neoforged.fml.config.IConfigSpec`，javap 证实）。
- 注意：**不要在 defineList 的 Supplier lambda 里直接执行 push**（lambda 惰性执行，只有在实际写入/生成 TOML 时才调用，多次调用会累积 push）。正确写法是把 push 交给 StageSpec 构造器并在其中立即 define+pop，保证 push/pop 在 lambda 内配对：

**修正后的代码示例（对照设计说明 §3）：**

```java
public final class SneakFallConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec SPEC;

    public static final List<StageSpec> STAGES; // 由 defineList 的 ConfigValue 得到

    static {
        var stages = BUILDER.defineList("stages", ArrayList::new,
                () -> new StageSpec(BUILDER.push("stage")),   // 元素供应商：push 后交给 StageSpec 构造
                obj -> obj instanceof StageSpec);              // 必须补的元素校验器（第 4 参）
        STAGES = stages.get();
        SPEC = BUILDER.build();
    }

    public static class StageSpec {
        public final String heightRange;
        public final SneakFallConfig.Mode mode;          // defineEnum 的枚举
        public final double qteWindow;
        public final ReductionMode reductionMode;
        public final double reductionValue;
        public StageSpec(ModConfigSpec.Builder b) {       // 构造器内 define 后立即 pop
            b.push("stage");                               // 若调用方已 push 则此行可省，但显式配对更稳
            this.heightRange  = b.define("heightRange", "all").get();
            this.mode         = b.defineEnum("mode", Mode.SUSTAIN).get();
            this.qteWindow    = b.defineInRange("qteWindow", 0.4, 0.0, 60.0).get();
            this.reductionMode = b.defineEnum("reductionMode", ReductionMode.DISTANCE).get();
            this.reductionValue = b.defineInRange("reductionValue", 0.0, 0.0, 1.0).get();
            b.pop();
        }
    }
}
```

（说明：`obj -> obj instanceof StageSpec` 或 `obj -> true` 均可。若想允许玩家把 stages 留空，用 `defineListAllowEmpty` 同签名版本，否则空列表会被校正。）

**依据：**
- javap：`/tmp/apirep/javap_Builder.txt`（8 个 defineList 重载 + 8 个 defineListAllowEmpty + defineEnum 16 个重载 + push/pop；不存在 `(String,Supplier,Supplier)` 三参）
- 源码：neoforge sources `ModConfigSpec.java` L397-612（defineList）、L633-642（defineEnum）、L842-864（push/pop）

---

## 4. Player.causeFallDamage / calculateFallDamage / LivingFallEvent 触发位置

**结论：事件在伤害计算之前触发，`setDistance`/`setDamageMultiplier` 的修改直接生效。伤害公式与设计说明一致（默认属性下即 `ceil((fallDistance-3)*multiplier)`），但有两个属性层细节。取消事件 = 完全无伤害（含摔落音效）。**

**调用链（NeoForge 补丁后的源码，minecraft-sources.jar 实测）：**

1. `Player.causeFallDamage(float, float, DamageSource)`（Player.java L1567-1597）：
   - `if (this.mayFly())`（创造/旁观）→ 触发 **`PlayerFlyableFallEvent`**（`EventHooks.onPlayerFall`），直接 `return false`。注意：飞行玩家走的不是 LivingFallEvent。
   - 否则若 `fallDistance >= 2.0F` 记统计，然后**委托 `super.causeFallDamage(...)`**（即 LivingEntity 版本，L1588）。
2. `LivingEntity.causeFallDamage`（补丁源码 L1609-1625）——**事件在这里触发**：
   ```java
   public boolean causeFallDamage(float dist, float mult, DamageSource src) {
       float[] ret = net.neoforged.neoforge.common.CommonHooks.onLivingFall(this, dist, mult);
       if (ret == null) return false;      // 事件被取消 → 直接无伤返回
       dist = ret[0];  mult = ret[1];      // 监听器修改的 distance/multiplier 写回局部变量
       boolean flag = super.causeFallDamage(dist, mult, src);
       int i = this.calculateFallDamage(dist, mult);   // 伤害计算在事件之后
       if (i > 0) { playSound(...); playBlockFallSound(); this.hurt(src, (float)i); return true; }
       return flag;
   }
   ```
3. `CommonHooks.onLivingFall`（CommonHooks.java L370-374）：
   ```java
   @Nullable
   public static float[] onLivingFall(LivingEntity entity, float distance, float damageMultiplier) {
       LivingFallEvent event = new LivingFallEvent(entity, distance, damageMultiplier);
       return (NeoForge.EVENT_BUS.post(event).isCanceled()
               ? null
               : new float[] { event.getDistance(), event.getDamageMultiplier() });
   }
   ```
   → 事件在 **`NeoForge.EVENT_BUS`（游戏总线）** 触发；取消返回 null；否则把**修改后**的值带回。
4. `LivingEntity.calculateFallDamage`（L1627-1635）：
   ```java
   protected int calculateFallDamage(float fallDistance, float multiplier) {
       if (this.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) return 0;
       float f  = (float) this.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);      // 默认 3.0
       float f1 = fallDistance - f;
       return Mth.ceil((double)(f1 * multiplier)
               * this.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));          // 默认 1.0
   }
   ```
   - **设计说明中的公式 `ceil((fallDistance-3)*multiplier)` 确认成立**（`SAFE_FALL_DISTANCE` 基础值 3.0、`FALL_DAMAGE_MULTIPLIER` 基础值 1.0，均为属性，玩家默认无修改时即此公式）。
   - 细节 1：安全距离是**属性**（盔甲等可改，如 Feather Falling 走的是 multiplier 属性），但普通生存玩家 = 3.0。
   - 细节 2：`Mth.ceil(f1 * multiplier * 属性)` 的取整是**最后一步**——PERCENT 减免在小伤害时被 ceil 吞掉的判断（设计说明 §2.4）属实；DISTANCE 减免同样在 ceil 前线性生效。
   - 细节 3：减免到 `i == 0` 时连摔落音效都不会播放（`if (i > 0)` 才 playSound）。

**代码示例（FallDamageHandler 核心，完全可行）：**

```java
@SubscribeEvent
public static void onLivingFall(LivingFallEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    // SUSTAIN: player.isCrouching()；QTE: tracker.isQteActive(player, windowTicks)
    // 通过后：event.setDistance(...) 或 event.setDamageMultiplier(...) —— 立即生效
}
```

**依据：**
- 补丁源码：`minecraft-sources.jar` `net/minecraft/world/entity/player/Player.java` L1567-1597；`net/minecraft/world/entity/LivingEntity.java` L1608-1635
- 补丁源码：neoforge-sources.jar `CommonHooks.java` L370-374、`EventHooks.java` L585-587（onPlayerFall → PlayerFlyableFallEvent）

---

## 5. Player.isCrouching() 与 ServerPlayer.serverTickCount（字段名是 tickCount，不是 serverTickCount）

**结论：`isCrouching()` 可用；`serverTickCount` 这个名字不存在，mojmap 名是 `tickCount`，public 字段，ServerPlayer 直接继承。**

- `Entity.isCrouching()`（Entity.java L2267-2269，public）：
  ```java
  public boolean isCrouching() {
      return this.hasPose(Pose.CROUCHING);
  }
  ```
  Player 直接继承，任意端可调用。
- **`ServerPlayer` 没有 `serverTickCount` 字段**（源码全文检索无声明）。服务端 tick 计数器是 **`Entity.tickCount`**（Entity.java L195：`public int tickCount;`，public 实例字段，ServerPlayer 多处使用 `this.tickCount`，如 ServerPlayer.java L618 `this.tickCount % 20 == 0`）。Yarn 所称 `serverTickCount` 对应的就是它。
  - 注意：客户端实体也有同名字段，所以只在 `level().isClientSide()==false` 分支使用才语义正确（配合 §2 的 isClientSide 检查）。

**代码示例（QTE 起始时刻记录）：**

```java
public static void onPlayerTick(PlayerTickEvent.Pre event) {
    Player p = event.getEntity();
    if (p.level().isClientSide() || !(p instanceof ServerPlayer sp)) return;
    boolean crouching = sp.isCrouching();
    if (crouching) {
        START.putIfAbsent(sp.getUUID(), sp.tickCount);   // public 字段，无需反射/AT
    } else {
        START.remove(sp.getUUID());                       // 松开即清零（QTE 防漏洞规则 2）
    }
}
```

**依据：**
- 源码：`minecraft-sources.jar` `Entity.java` L195（`public int tickCount;`）、L2267（isCrouching）；`ServerPlayer.java`（无 tickCount 声明，仅继承使用）

---

## 6. NeoForge 1.21.1 COMMON config 注册方式（用 ModContainer.registerConfig，ModLoadingContext 已无此方法）

**结论：用 `modContainer.registerConfig(ModConfig.Type.COMMON, SPEC)`。`ModLoadingContext.registerConfig` 已被移除（类还在，方法没了）。注意 import 路径：`net.neoforged.fml.config.ModConfig`（不是旧 Forge 的 `net.neoforged.neoforge.fml.config`）。**

javap 实测（loader-4.0.38.jar，即 21.1.248 的 fml）：

```java
// net.neoforged.fml.ModContainer:
public void registerConfig(net.neoforged.fml.config.ModConfig$Type, net.neoforged.fml.config.IConfigSpec);
public void registerConfig(net.neoforged.fml.config.ModConfig$Type, net.neoforged.fml.config.IConfigSpec, java.lang.String); // 自定义文件名

// net.neoforged.fml.config.ModConfig$Type: COMMON / CLIENT / SERVER / STARTUP（枚举常量实测存在）

// net.neoforged.fml.ModLoadingContext: 只剩 get()/setActiveContainer/getActiveContainer/getActiveNamespace/registerExtensionPoint —— 没有 registerConfig
```

- 默认文件名（ConfigTracker 私有方法 `defaultConfigName` 字节码实测）：`String.format("%s-%s.toml", modId, type.extension())`，`extension() = name().toLowerCase()`。→ sneakfall + COMMON = **`config/sneakfall-common.toml`**，与设计说明 §3 完全一致。
- `@Mod` 主类构造器注入 `(IEventBus, ModContainer)` 的写法在 21.1 有效（另一已建成 NeoForge 项目的主类实测即如此，moddev 2.0.42-beta 构建通过）。

**代码示例（SneakFall.java 主类）：**

```java
package me.shinano.sneakfall;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;                 // 注意包名
import net.neoforged.neoforge.common.NeoForge;

@Mod(SneakFall.MODID)
public class SneakFall {
    public static final String MODID = "sneakfall";

    public SneakFall(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, SneakFallConfig.SPEC); // 注册方式
        // 游戏事件（PlayerTickEvent / LivingFallEvent / PlayerLoggedOutEvent）注册到游戏总线：
        NeoForge.EVENT_BUS.addListener(FallDamageHandler::onLivingFall);
        NeoForge.EVENT_BUS.addListener(SneakFallTracker::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(SneakFallTracker::onPlayerLoggedOut);
    }
}
```

**依据：**
- javap：`/tmp/apirep/javap_ModContainer.txt`（registerConfig 两个重载，loader-4.0.38.jar）
- javap：`/tmp/apirep/javap_ModConfig.txt` + `javap_ModConfigType.txt`（Type 枚举 COMMON/CLIENT/SERVER/STARTUP、`extension()`）
- javap：`/tmp/apirep/javap_ModLoadingContext.txt`（无 registerConfig）
- javap：`/tmp/apirep/javap_ConfigTracker.txt`（`defaultConfigName` → `"%s-%s.toml"`）
- 参考项目主类源码 L28-36（`@Mod(...)` + `(IEventBus, ModContainer)` 构造器 + `NeoForge.EVENT_BUS.addListener`）

---

## 附：对设计说明的修正清单（必须改动的点）

| # | 设计说明原文 | 实测结论 | 修正 |
|---|------------------|----------|------|
| 1 | §3 `defineList("stages", ArrayList::new, () -> new StageSpec(BUILDER.push("stage")))` | 三参 (String, Supplier, Supplier) 不存在 | 补第 4 参元素校验器：`defineList("stages", ArrayList::new, () -> new StageSpec(BUILDER.push("stage")), obj -> obj instanceof StageSpec)`（§3 已给全示例） |
| 2 | §4 `PlayerTickEvent.PRE` | `Phase` 枚举不存在，改用内部类 | `PlayerTickEvent.Pre`（§2 已给全示例） |
| 3 | §4 "qte 起始 server tick" 未给字段 | `serverTickCount` 不存在 | 用 `sp.tickCount`（public，继承自 Entity；§5 已给示例） |
| 4 | §2 未提事件取消语义 | `isCancelable()` 方法不存在；取消 = ICancellableEvent | 用 `event.isCanceled()`；本模组不必取消事件，仅改数值 |
| 5 | §2.3 公式 `ceil((fallDistance-3)*multiplier)` | 成立，但 3.0 是 `SAFE_FALL_DISTANCE` 属性、末尾还乘 `FALL_DAMAGE_MULTIPLIER` 属性（默认 1.0） | 公式对普通生存玩家精确成立；无需改动设计，在此记录即可 |
| 6 | §1 配置名 `sneakfall-common.toml` | 字节码 `%s-%s.toml`（modId, type.extension()） | 一致，无需改动 |
| 7 | §1/§4 "无 mixin/AT" | 全部 API 均为 public，事件全部可用 | 成立，零 mixin/零 AT 可行 |

未发现其他破坏性差异。LivingFallEvent 对所有 LivingEntity 触发（非仅玩家），处理器内 `instanceof ServerPlayer` 过滤为必要步骤。
