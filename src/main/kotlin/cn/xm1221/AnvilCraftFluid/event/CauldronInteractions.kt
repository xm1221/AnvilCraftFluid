package cn.xm1221.AnvilCraftFluid.event

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import net.minecraft.core.cauldron.CauldronInteraction
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.level.block.Blocks
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent

/**
 * 补上"用桶把流体倒进我们的炼药锅"这条交互。
 *
 * ## 为什么必须自己加
 *
 * NeoForge 的 `CauldronFluidContent`（我们在
 * [CauldronFluidContentRegistry] 里登记过）**只提供两件事**：
 * 1. 锅 ↔ 流体的信息映射（`getForBlock` / `getForFluid`、层数与容量换算）；
 * 2. 给锅方块注册 `Capabilities.FluidHandler.BLOCK` 能力（`CauldronWrapper`，给管道 / 机器用）。
 *
 * 它**不负责桶的交互**——那套走的是原版 `CauldronInteraction`：
 * `AbstractCauldronBlock#useItemOn` 拿手里的物品去 `this.interactions.map().get(item)` 查表。
 * 所以不放条目的话，用桶右键我们的锅会直接 `PASS`，桶的 `useOn` 接着把流体**倒在地图上**，
 * 表现就是"倒不进炼药锅"。
 *
 * ## 为什么放到 `FMLCommonSetupEvent`
 *
 * 条目要以**我们的桶物品实例**为键，而桶是在 [AddonFluids] 注册流体时由
 * `FluidBuilder.bucket()` 创建的；mod 构造阶段注册表还没填充，
 * `fluid.getSource().bucket` 会直接抛 `unbound value`。
 * 因此等 `FMLCommonSetupEvent`（注册表已冻结）再把条目补进交互表——
 * 交互表是运行时查的，晚填完全没问题。
 *
 * 补的条目：
 * | 手持 | 目标 | 行为 |
 * | --- | --- | --- |
 * | `<name>_bucket` | 空炼药锅 | 用原版 `CauldronInteraction.emptyBucket` 换成对应的满锅 |
 * | `<name>_bucket` | 已经有东西的锅（含我们自己的满锅） | 吃掉这次点击，避免桶把流体倒到世界里 |
 *
 * （空桶从锅里舀出那条在 [AddonFluids] 注册时就加了，用的是原版物品 `Items.BUCKET`，构造期安全。）
 */
object CauldronInteractions {

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        event.enqueueWork {
            var added = 0
            AddonFluids.REGISTERED.forEach { registered ->
                val bucket = registered.bucket ?: return@forEach
                registered.interactions.map()[bucket] =
                    CauldronInteraction { state, level, pos, player, hand, stack ->
                        if (state.`is`(Blocks.CAULDRON)) {
                            CauldronInteraction.emptyBucket(
                                level,
                                pos,
                                player,
                                hand,
                                stack,
                                registered.cauldron.get().defaultBlockState(),
                                SoundEvents.BUCKET_EMPTY_LAVA,
                            )
                        } else {
                            ItemInteractionResult.sidedSuccess(level.isClientSide)
                        }
                    }
                added++
            }
            AnvilCraftFluid.LOGGER.debug("Added bucket→cauldron interactions for {} fluid(s)", added)
        }
    }
}
