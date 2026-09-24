package cn.xm1221.AnvilCraftFluid.event

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import net.minecraft.core.cauldron.CauldronInteraction
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.ItemInteractionResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent

/**
 * 补上"用桶把流体倒进炼药锅"这条交互。
 *
 * ## 为什么必须自己加
 *
 * NeoForge 的 `CauldronFluidContent`（我们在
 * [CauldronFluidContentRegistry] 里登记过）**只提供两件事**：
 * 1. 锅 ↔ 流体的信息映射（`getForBlock` / `getForFluid`、层数与容量换算）；
 * 2. 给锅方块注册 `Capabilities.FluidHandler.BLOCK` 能力（`CauldronWrapper`，给管道 / 机器用）。
 *
 * 它**不负责桶的交互**——那套走原版 `CauldronInteraction`：
 * `AbstractCauldronBlock#useItemOn` 拿手里的物品去 `this.interactions.map().get(item)` 查表，
 * 查不到就 `PASS`，接着桶的 `useOn` 把流体**倒在地图上**，表现就是"倒不进炼药锅"。
 *
 * ## ⚠️ 关键是"空锅用哪张表"
 *
 * 原版把交互表按锅的种类分开了，**空炼药锅用的是 [CauldronInteraction.EMPTY]**：
 *
 * | 锅 | 用的表 |
 * | --- | --- |
 * | `minecraft:cauldron`（空锅） | `CauldronInteraction.EMPTY` |
 * | `minecraft:water_cauldron` | `CauldronInteraction.WATER` |
 * | `minecraft:lava_cauldron` | `CauldronInteraction.LAVA` |
 * | `minecraft:powder_snow_cauldron` | `CauldronInteraction.POWDER_SNOW` |
 * | 我们的 `<name>_cauldron` | 注册时 `newInteractionMap` 出来的那张 |
 *
 * 只往"我们自己的锅"那张表里加条目是没用的——玩家是拿着桶右键**空锅**。
 * 所以这里把条目同时塞进上面四张原版表 + 我们自己那张。
 *
 * ## 为什么放到 `FMLCommonSetupEvent`
 *
 * 条目要以**我们的桶物品实例**为键，而桶是在 [AddonFluids] 注册流体时由
 * `FluidBuilder.bucket()` 创建的；mod 构造阶段注册表还没填充，
 * `fluid.getSource().bucket` 会直接抛 `unbound value`。
 * 因此等 `FMLCommonSetupEvent`（注册表已冻结）再把条目补进交互表——
 * 交互表是运行时查的，晚填完全没问题。
 */
object CauldronInteractions {

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        event.enqueueWork {
            // 原版四种锅使用的交互表 + 我们自己的锅的表
            val vanillaMaps = listOf(
                CauldronInteraction.EMPTY,
                CauldronInteraction.WATER,
                CauldronInteraction.LAVA,
                CauldronInteraction.POWDER_SNOW,
            )

            var added = 0
            AddonFluids.REGISTERED.forEach { registered ->
                val bucket = registered.bucket ?: return@forEach
                val fullCauldron = registered.cauldron.get()

                val interaction = CauldronInteraction { state, level, pos, player, hand, stack ->
                    if (state.`is`(fullCauldron)) {
                        // 已经是同一种满锅：吃掉这次点击，不重复消耗桶
                        ItemInteractionResult.sidedSuccess(level.isClientSide)
                    } else {
                        // 空锅 / 水锅 / 岩浆锅 / 细雪锅 / 别的流体的锅 → 替换成我们的满锅
                        // （与原版"岩浆桶倒进水锅会把水锅换成岩浆锅"的行为一致）
                        CauldronInteraction.emptyBucket(
                            level,
                            pos,
                            player,
                            hand,
                            stack,
                            fullCauldron.defaultBlockState(),
                            SoundEvents.BUCKET_EMPTY_LAVA,
                        )
                    }
                }

                vanillaMaps.forEach { it.map()[bucket] = interaction }
                registered.interactions.map()[bucket] = interaction
                added++
            }
            AnvilCraftFluid.LOGGER.debug(
                "Registered bucket→cauldron interactions for {} fluid(s) across 4 vanilla maps + own maps",
                added,
            )
        }
    }
}
