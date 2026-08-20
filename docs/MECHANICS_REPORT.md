# MECHANICS_REPORT — MC 摔落伤害机制与同类模组调研

> 调研过程中网络搜索超时，本文档为事后补全。超时前已取到全部关键材料（Minecraft Wiki 缓存、NeoForge patch 源码、Modrinth 搜索命中），未取部分仅剩 Modrinth 详情页（JS 渲染抓取失败），不影响结论。

## 1. MC 1.21.x 摔落伤害精确机制

| 事实 | 结论 |
|------|------|
| 伤害公式（1.21.1 源码，见 API_REPORT §4） | `damage = Mth.ceil((fallDistance − SAFE_FALL_DISTANCE) × multiplier × FALL_DAMAGE_MULTIPLIER)`，玩家默认属性下即 `ceil((fallDistance − 3.0) × multiplier)` |
| fallDistance 怎么积累 | **基于 Y 坐标变化，与速度无关**（Wiki 原文："Fall damage is based on the change in an entity's Y coordinate, and not how fast the entity is moving"）→ **高度区间判定是正确的设计选择**（设计说明 §2.3 的判断成立） |
| 每点伤害 | 1 HP = 半颗心（玩家 20 HP） |
| 安全高度 | 3 格（`safe_fall_distance` 属性，玩家默认 3.0，可被 mod 修改） |
| 致死高度 | 满血 23.5 格（游戏先算伤害再更新 fallDistance，故 23 格整不死） |
| 取整 | `Mth.ceil` 最后取整 → **小伤害时 PERCENT 减免会被 ceil 吞掉**（如 1 点伤害 ×0.5 = 0.5 → ceil = 1，不减）。DISTANCE（减等效格数）在 ceil 前线性生效，低高度也有效 → 默认推荐 DISTANCE（设计说明 §2.4 的判断成立） |
| 事件时序 | LivingFallEvent 在 `LivingEntity.causeFallDamage` 内、伤害计算**之前**触发，setDistance/setDamageMultiplier 立即写回生效（API_REPORT §4 源码证据） |
| 减免到 0 | 伤害为 0 时连摔落音效都不播放（`if (i > 0)` 才 playSound）——完全无伤落地会静默，属正常表现 |
| 创造/飞行玩家 | 走 `PlayerFlyableFallEvent`，**不经 LivingFallEvent** → 本模组对创造模式自然不生效（生存向功能，符合预期） |

## 2. 官方原生减伤途径（设计的合理性佐证）

- **潜行防坠崖**：潜行状态下不会从 >0.5 格高度差掉落（Wiki 原文）。→ "蹲着走出悬崖"根本不会坠落；坠落中的玩家必是起跳/被击退后空中按 shift。QTE 语义"必须坠落途中及时按下"自然成立。
- **潜行+脚手架 = 任意高度免摔伤**（原版机制）——原版已有"潜行落地免伤"先例，本模组将其泛化为独立机制，直觉一致。
- 黏液块：潜行落地免伤且不弹跳。
- 其他：羽毛坠落/保护附魔、缓降药水（全免）、跳跃提升（每级减 1 HP）、抗性（每级 20%）、干草块/蜂蜜块（减到 20%）。

## 3. 同类模组（CurseForge / Modrinth）

| 模组 | 平台/版本 | 思路 | 与本模组差异 |
|------|-----------|------|--------------|
| **Crouch Cushion**（ErdeiFarkasGyula） | Modrinth `sS2OeY9R` / CurseForge 7813325，**NeoForge 1.21.1** | "落地前蹲下减免摔落伤害"，1.2K+ 下载 | 同款核心玩法。**本模组差异**：多阶段高度区间、SUSTAIN/QTE 双判定、DISTANCE/PERCENT 双减免语义、保底 fallback——配置丰富度远超 |
| SimpleMovement | Modrinth `UdDGX59K` | "落地前 0.1s 蹲下 = 免摔伤，2s 冷却" | 极简 QTE，无配置 |
| Sneak Actions | Modrinth `OPtzRc0w` | 数据包实现 | 数据包路线，非模组 |

结论：需求可满足，技术路线（纯事件、无 mixin）与现有模组主流一致；本模组以可配置性和阶段化设计区别于同类模组，有独立价值。

## 4. 其他游戏 QTE 参考

落地前限时按键减免坠落伤害的 QTE 设计常见于动作游戏（如《蜘蛛侠》《黑神话》类受身操作）。此处仅作参考，不展开。

## 5. 对设计说明的影响

- 高度判定确认正确（fallDistance 与速度无关）。
- 公式确认（API_REPORT §4 双重验证）。
- QTE 窗口换算 1 秒 = 20 tick 确认（MC tick 50ms）。
- 机制设计无需修改，仅按 API_REPORT 修正清单调整代码级 API 用法。
