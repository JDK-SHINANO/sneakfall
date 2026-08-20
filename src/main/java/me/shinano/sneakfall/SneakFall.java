package me.shinano.sneakfall;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(SneakFall.MODID)
public class SneakFall {
    public static final String MODID = "sneakfall";

    private static final Logger LOGGER = LogUtils.getLogger();

    public SneakFall(IEventBus modEventBus, ModContainer modContainer) {
        // 注册配置文件：生成于 config/sneakfall-common.toml，含阶段、模式、减免值等全部可调参数
        modContainer.registerConfig(ModConfig.Type.COMMON, SneakFallConfig.SPEC);

        // 注册游戏事件监听：摔落伤害减免、潜行 QTE 追踪、退出清理
        NeoForge.EVENT_BUS.addListener(FallDamageHandler::onLivingFall);
        NeoForge.EVENT_BUS.addListener(SneakFallTracker::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(SneakFallTracker::onPlayerLoggedOut);

        // 配置加载完成后打印阶段摘要（此时读到的才是用户配置文件中的值）
        modEventBus.addListener(SneakFall::onCommonSetup);

        LOGGER.info("[SneakFall] 轻功已就绪——坠地前按下潜行，化千钧为鸿毛。");
    }

    /**
     * FMLCommonSetupEvent 在配置文件加载（LoadingConfigsEvent）之后触发，
     * 此处解析并输出各高度档位的减免规则，保证日志反映用户实际配置。
     */
    private static void onCommonSetup(FMLCommonSetupEvent event) {
        SneakFallConfig.logStages();
    }
}
