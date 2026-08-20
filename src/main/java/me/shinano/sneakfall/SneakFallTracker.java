package me.shinano.sneakfall;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 潜行状态追踪器：记录每位玩家开始潜行（按下潜行键）时的 tickCount，
 * 用于 QTE 模式判断「是否在坠落窗口内正按着潜行」。
 */
public final class SneakFallTracker {

    // 潜行起始 tickCount：key 为玩家 UUID，value 为按下潜行那一刻的 tickCount
    private static final Map<UUID, Long> CROUCH_START_TICK = new ConcurrentHashMap<>();

    /**
     * 玩家每 tick 事件（Pre 阶段），在服务端记录/清除潜行起始 tick。
     * 只处理服务端：客户端数据不可信，避免作弊。
     */
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        // 只处理服务端：客户端 tick 不计入 QTE 窗口，防止利用客户端伪造
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        if (sp.isCrouching()) {
            // 按下潜行记录起始 tick，putIfAbsent 保证按住期间不重置——QTE 窗口从「按下瞬间」起算
            CROUCH_START_TICK.putIfAbsent(sp.getUUID(), (long) sp.tickCount);
        } else {
            // 松开潜行立即清零——QTE 防漏洞硬规则：不允许先按再松、落地瞬间再按来骗过窗口
            CROUCH_START_TICK.remove(sp.getUUID());
        }
    }

    /**
     * 判断玩家的潜行 QTE 是否处于激活状态：
     * 必须「当前正按着潜行」且「距按下瞬间未超过 windowTicks 窗口」。
     *
     * @param sp          目标玩家
     * @param windowTicks QTE 判定窗口（单位：tick）
     * @return true 表示 QTE 有效
     */
    public static boolean isQteActive(ServerPlayer sp, int windowTicks) {
        Long start = CROUCH_START_TICK.get(sp.getUUID());
        // 必须正按着潜行（map 中有记录）且未超窗口，二者缺一不可
        return start != null && (sp.tickCount - start) <= windowTicks;
    }

    /**
     * 玩家退出服务器事件：移除其潜行记录。
     * 防止玩家反复进出导致 map 中残留脏数据（内存泄漏）。
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 玩家退出清理防泄漏
        CROUCH_START_TICK.remove(event.getEntity().getUUID());
    }
}
