package cn.xm1221.AnvilCraftFluid.data

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.data.lang.AddonLangHandler
import dev.anvilcraft.lib.v2.registrum.providers.ProviderType
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.data.event.GatherDataEvent

@EventBusSubscriber(modid = AnvilCraftFluid.MOD_ID)
class AddonDatagen {
    companion object {
        @SubscribeEvent
        @JvmStatic
        fun gatherData(event: GatherDataEvent) {
        }

        /**
         * 初始化生成器
         */
        fun init() {
            AnvilCraftFluid.REGISTRUM.addDataGenerator(ProviderType.LANG, AddonLangHandler::init)
        }
    }
}
