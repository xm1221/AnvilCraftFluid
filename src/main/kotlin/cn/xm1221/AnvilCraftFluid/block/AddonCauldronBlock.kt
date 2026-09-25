package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.fluid.FluidContactApplier
import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec
import com.mojang.serialization.MapCodec
import dev.dubhe.anvilcraft.api.hammer.IHammerRemovable
import dev.dubhe.anvilcraft.block.better.BetterAbstractCauldronBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.cauldron.CauldronInteraction
import net.minecraft.world.entity.Entity
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
 *
 * ## 站在锅里的影响
 *
 * 炼药锅不是实心方块（原版就有 4 像素的锅底可以站进去），所以"泡在锅里"与
 * "泡在世界流体里"是同一件事：这里同样把 [FluidSpec.contact] 施加给实体
 * （余烬锅烧人、浮霜锅冻人……）。判定用基类的 [isEntityInsideContent]，
 * 因此站在**锅沿**上不会被烫。
 *
 * @param interactionMap 该锅的桶交互表
 * @param spec 该流体的定义；影响与上游"重铸修复"都在 `spec.contact` 里
 */
class AddonCauldronBlock(
    properties: Properties,
    private val interactionMap: CauldronInteraction.InteractionMap,
    private val spec: FluidSpec,
) : BetterAbstractCauldronBlock(properties, interactionMap), IHammerRemovable {

    override fun codec(): MapCodec<out AbstractCauldronBlock> =
        simpleCodec { props -> AddonCauldronBlock(props, interactionMap, spec) }

    override fun getContentHeight(state: BlockState): Double = 0.9375

    override fun isFull(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int = 3

    /**
     * 掉进锅里的东西同样吃这一套影响。
     *
     * 上游的重铸修复（余烬金属装备在火 / 灵魂火 / 岩浆里每 tick 回耐久）也走这里：
     * 它由 `spec.contact.reforgePerTick` 打开，与"泡在世界流体里"是同一份代码。
     */
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        super.entityInside(state, level, pos, entity)
        if (!isEntityInsideContent(state, pos, entity)) return
        FluidContactApplier.apply(spec, level, pos, entity)
    }
}
