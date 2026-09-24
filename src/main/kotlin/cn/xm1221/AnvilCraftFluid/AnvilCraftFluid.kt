package cn.xm1221.AnvilCraftFluid

import cn.xm1221.AnvilCraftFluid.data.AddonDatagen
import cn.xm1221.AnvilCraftFluid.event.CauldronFluidContentRegistry
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import cn.xm1221.AnvilCraftFluid.init.AddonItemGroups
import com.mojang.logging.LogUtils
import dev.anvilcraft.lib.v2.config.ConfigManager
import dev.anvilcraft.lib.v2.registrum.Registrum
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
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
        // 流体 + 桶 + 液体方块 + 炼药锅（必须最先注册，后面的注册项会引用它们）
        AddonFluids.register()
        AddonItemGroups.register(modEventBus)

        // 把锅↔流体登记进 NeoForge 的 CauldronFluidContent
        // （该事件实现 IModBusEvent，所以挂 mod 总线）
        modEventBus.addListener<RegisterCauldronFluidContentEvent> { event ->
            CauldronFluidContentRegistry.registerCauldronFluidContent(event)
        }

        AddonDatagen.init()
    }
}
