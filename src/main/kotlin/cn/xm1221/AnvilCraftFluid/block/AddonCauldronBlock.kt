package cn.xm1221.AnvilCraftFluid.block

import com.mojang.serialization.MapCodec
import dev.dubhe.anvilcraft.api.hammer.IHammerRemovable
import dev.dubhe.anvilcraft.block.better.BetterAbstractCauldronBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.cauldron.CauldronInteraction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * 本模组通用的"满锅"炼药锅（对应 NeoForge 的
 * `RegisterCauldronFluidContentEvent#register(block, fluid, amount, levelProperty = null)`）。
 *
 * 行为对齐 AnvilCraft 的 `MeltGemCauldron`：
 * - [isFull] 恒为 true（没有层数状态，一锅就是 1000 mB）
 * - [getContentHeight] = 0.9375（原版水锅装满时的高度）
 * - 比较器输出固定 3
 * - 用铁砧锤可以敲下来（[IHammerRemovable]）
 *
 * 锅方块**必须**命名为 `<流体注册名>_cauldron`，否则 AnvilCraft 的
 * `HasCauldron#getDefaultCauldron` 找不到它，配方会静默匹配失败。
 */
class AddonCauldronBlock(
    properties: Properties,
    private val interactionMap: CauldronInteraction.InteractionMap,
) : BetterAbstractCauldronBlock(properties, interactionMap), IHammerRemovable {

    override fun codec(): MapCodec<out AbstractCauldronBlock> =
        simpleCodec { props -> AddonCauldronBlock(props, interactionMap) }

    override fun getContentHeight(state: BlockState): Double = 0.9375

    override fun isFull(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int = 3
}
