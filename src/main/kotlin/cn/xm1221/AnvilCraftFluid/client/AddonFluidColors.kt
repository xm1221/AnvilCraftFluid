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
 * 世界流体（流动的液体）本身不走这里——它由 [cn.xm1221.AnvilCraftFluid.init.AddonFluids] 里
 * `IClientFluidTypeExtensions#getTintColor` 染色。本类负责另外两个载体：
 *
 * | 载体 | 贴图 | 染色入口 |
 * | --- | --- | --- |
 * | 炼药锅内液面 | 复用 `block/<name>_still` | 方块颜色处理器，模型的 content 面是 `tintindex: 0` |
 * | 桶 | `item/bucket.png`（不染）+ `item/bucket_fluid.png`（染） | 物品颜色处理器，`item/generated` 的第 n 层就是 `tintindex: n` |
 *
 * 因此这些贴图同样只要画灰度图。
 *
 * ## 桶的描边
 *
 * 有 [cn.xm1221.AnvilCraftFluid.fluid.FluidSpec.bucketOutlineTint] 的流体的桶多一层
 * `layer2`（白色描边图 `item/bucket_fluid_outline`），颜色就取那个值。
 *
 * ⚠️ 它和世界液面那圈边框**不是同一个数据源**：世界边框的颜色来自预上色贴图
 * （`<name>_metal_block_outline`，见 `client/FluidOutlineRenderer` 用的是白色顶点色），
 * 这里的颜色是 spec 里单独写的常量。所以改边框贴图时要记得同步这个常量。
 */
@EventBusSubscriber(modid = AnvilCraftFluid.MOD_ID, value = [Dist.CLIENT])
object AddonFluidColors {

    /** 炼药锅染色：0 = 锅内液面本色，1 = 锅内液面那圈边框 */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterBlockColors(event: RegisterColorHandlersEvent.Block) {
        AddonFluids.REGISTERED.forEach { registered ->
            event.register(
                { _, _, _, tintIndex ->
                    when (tintIndex) {
                        0 -> registered.spec.tint
                        // 锅里的胶体不会被红石激活（只有世界里的流体方块导电），
                        // 所以固定用"未激活"那一档颜色；浮霜余烬没有 tint 字段 → -1 = 贴图原样
                        1 -> registered.spec.outlineTintOff ?: registered.spec.outlineTintOn ?: -1
                        else -> -1
                    }
                },
                registered.cauldron.get(),
            )
        }
    }

    /** 桶染色：layer1 = 桶内液体（[FluidSpec.bucketFluidTint]，默认取 [FluidSpec.tint]），layer2 = 描边（[FluidSpec.bucketOutlineTint]） */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterItemColors(event: RegisterColorHandlersEvent.Item) {
        AddonFluids.REGISTERED.forEach { registered ->
            val bucket = registered.bucket ?: return@forEach
            // 桶内液体那一层是公用灰度图，所以它单独要颜色：
            // 世界贴图自带成品色的流体（tint = 白）就靠 bucketFluidTint 出颜色
            val fluidTint = registered.spec.bucketFluidTint ?: registered.spec.tint
            val outline = registered.spec.bucketOutlineTint
            event.register(
                { _, tintIndex ->
                    when (tintIndex) {
                        1 -> fluidTint
                        2 -> outline ?: -1
                        else -> -1
                    }
                },
                bucket,
            )
        }
    }
}
