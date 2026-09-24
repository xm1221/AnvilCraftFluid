package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent

/**
 * 灰度贴图的"内部上色"。
 *
 * 世界流体（流动的液体）不走这里——它由 [cn.xm1221.AnvilCraftFluid.init.AddonFluids] 里
 * `IClientFluidTypeExtensions#getTintColor` 染色。本类负责另外两个载体：
 *
 * | 载体 | 贴图 | 染色入口 |
 * | --- | --- | --- |
 * | 炼药锅内液面 | 复用 `block/<name>_still` | 方块颜色处理器，模型的 content 面是 `tintindex: 0` |
 * | 桶 | `item/<name>_bucket` | 物品颜色处理器，`item/generated` 的 layer0 自带 `tintindex: 0` |
 *
 * 因此这两张贴图同样只要画灰度图。
 */
@EventBusSubscriber(modid = AnvilCraftFluid.MOD_ID, value = [Dist.CLIENT])
object AddonFluidColors {

    /** 炼药锅液面染色 */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterBlockColors(event: RegisterColorHandlersEvent.Block) {
        AddonFluids.REGISTERED.forEach { registered ->
            event.register(
                { _, _, _, tintIndex -> if (tintIndex == 0) registered.spec.tint else -1 },
                registered.cauldron.get(),
            )
        }
    }

    /** 桶染色 */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterItemColors(event: RegisterColorHandlersEvent.Item) {
        AddonFluids.REGISTERED.forEach { registered ->
            val bucket = registered.bucket ?: return@forEach
            event.register(
                { _, tintIndex -> if (tintIndex == 0) registered.spec.tint else -1 },
                bucket,
            )
        }
    }
}
