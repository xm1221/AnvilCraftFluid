package cn.xm1221.AnvilCraftFluid.data

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.data.block.AddonBlockTagHandler
import cn.xm1221.AnvilCraftFluid.data.lang.AddonLangHandler
import cn.xm1221.AnvilCraftFluid.data.recipe.AddonRecipeHandler
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
            // 方块标签（`anvilcraft_fluid:wash_proof/redstone` 等）走标准 NeoForge provider：
            // 本模组的数据一律 datagen 生成，不手写 json，见 AddonBlockTagHandler 的类注释。
            event.generator.addProvider(
                event.includeServer(),
                AddonBlockTagHandler(
                    event.generator.packOutput,
                    event.lookupProvider,
                    event.existingFileHelper,
                ),
            )
        }

        /**
         * 初始化生成器
         */
        fun init() {
            AnvilCraftFluid.REGISTRUM.addDataGenerator(ProviderType.LANG, AddonLangHandler::init)
            AnvilCraftFluid.REGISTRUM.addDataGenerator(ProviderType.RECIPE, AddonRecipeHandler::init)
        }
    }
}
