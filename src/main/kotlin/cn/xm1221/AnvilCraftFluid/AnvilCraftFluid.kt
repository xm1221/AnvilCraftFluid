package cn.xm1221.AnvilCraftFluid

import cn.xm1221.AnvilCraftFluid.data.AddonDatagen
import cn.xm1221.AnvilCraftFluid.event.CauldronFluidContentRegistry
import cn.xm1221.AnvilCraftFluid.event.CauldronInteractions
import cn.xm1221.AnvilCraftFluid.event.CauldronItemReactions
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import cn.xm1221.AnvilCraftFluid.init.AddonItemGroups
import com.mojang.logging.LogUtils
import dev.anvilcraft.lib.v2.config.ConfigManager
import dev.anvilcraft.lib.v2.registrum.Registrum
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.fluids.RegisterCauldronFluidContentEvent
import org.jetbrains.annotations.NotNull
import org.slf4j.Logger

@Mod(AnvilCraftFluid.MOD_ID)
class AnvilCraftFluid(modEventBus: IEventBus, modContainer: ModContainer) {
    companion object {
        const val MOD_ID: String = "anvilcraft_fluid"
        val LOGGER: Logger = LogUtils.getLogger()
        val CONFIG: AddonConfig = ConfigManager.register(MOD_ID, ::AddonConfig)
        val REGISTRUM: Registrum = Registrum.create(MOD_ID)

        @NotNull
        fun of(path: String): ResourceLocation {
            return ResourceLocation.fromNamespaceAndPath(MOD_ID, path)
        }
    }

    init {
        // ⚠️ 顺序关键：先设默认创造标签页，再注册条目。
        // Registrum 在创建物品 builder 时会读一次 defaultCreativeModeTab，
        // 晚设的话桶只会落到原版搜索页（见 AddonItemGroups 的注释）。
        AddonItemGroups.register(modEventBus)

        // 流体 + 桶 + 液体方块 + 炼药锅
        AddonFluids.register()

        // 把锅↔流体登记进 NeoForge 的 CauldronFluidContent
        // （该事件实现 IModBusEvent，所以挂 mod 总线）
        modEventBus.addListener<RegisterCauldronFluidContentEvent> { event ->
            CauldronFluidContentRegistry.registerCauldronFluidContent(event)
        }

        // 桶 → 我们的炼药锅的交互条目（要等注册表填充后才能拿到桶物品实例）
        modEventBus.addListener<FMLCommonSetupEvent> { event ->
            CauldronInteractions.onCommonSetup(event)
        }

        // 大型炼药锅上的「流体 × 物品」反应（浮霜洗附魔 / 熔融金洗诅咒 / 余烬加速修复）。
        // LargeCauldronEvent 是 post 到 NeoForge.EVENT_BUS 的游戏总线事件，
        // 所以这里显式注册到游戏总线，不走 @EventBusSubscriber。
        NeoForge.EVENT_BUS.register(CauldronItemReactions)

        AddonDatagen.init()
    }
}
