package me.shinano.sneakfall;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/**
 * 摔落伤害处理器：在玩家落地造成摔落伤害前拦截事件，
 * 依据「潜行 + 阶段配置」对伤害进行减免。
 *
 * LivingFallEvent 在伤害计算前触发，修改 distance（等效坠落格数）或
 * damageMultiplier（伤害倍率）会立即作用于本次摔落伤害计算。
 */
public final class FallDamageHandler {

    /**
     * 摔落伤害事件处理入口。
     * 注意：本方法不加 @SubscribeEvent 注解，由主类构造器中 addListener 注册。
     */
    public static void onLivingFall(LivingFallEvent event) {
        // 仅生存模式玩家走此减免（创造/旁观或飞行状态走另一事件，此处不处理）
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // 按减免前坠落高度匹配阶段：config 中按高度分档，未覆盖的区间走保底阶段
        SneakFallConfig.RuntimeStage stage = SneakFallConfig.matchStage(event.getDistance());

        // 无减免：该阶段减免值为 0，直接返回，不做任何修改
        if (stage.reductionValue() <= 0.0) return;

        // 按住潜行才能减免（硬性要求）：站立摔落不享受任何减免
        if (!player.isCrouching()) return;

        // 按模式判定资格：SUSTAIN 持续潜行即有效；QTE 需在窗口内按下潜行
        boolean eligible = switch (stage.mode()) {
            case SUSTAIN -> true;
            case QTE -> SneakFallTracker.isQteActive(player, stage.qteWindowTicks());
        };
        if (!eligible) return;

        // 按减免方式应用修改
        switch (stage.reductionMode()) {
            // 减等效格数：直接从坠落距离中扣除，低高度坠落同样有效
            case DISTANCE -> event.setDistance(Math.max(0.0f, event.getDistance() - (float) stage.reductionValue()));
            // 比例减免：按百分比降低伤害倍率，小伤害可能因取整被吞掉（1 心以下常见）
            case PERCENT -> event.setDamageMultiplier(event.getDamageMultiplier() * (float) (1.0 - stage.reductionValue()));
        }
    }
}
