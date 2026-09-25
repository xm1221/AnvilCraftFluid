package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.fluid.FluidContactApplier
import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid

/**
 * 本模组通用的液体方块：**泡在里头会受到 [FluidSpec.contact] 里规定的影响**。
 *
 * 行为（着火 / 伤害 / 冻结 / 状态效果 / 烧毁物品 / 重铸修复）全在
 * [FluidContactApplier] 里，这里只负责把实体每 tick 的通知接过去。
 *
 * ## 为什么用 [LiquidBlock#entityInside] 而不是 mixin
 *
 * 原版与上游都是这么做的（`PowderSnowBlock`、上游的 `ExpFluidBlock`、
 * `LavaCauldronBlock` 都覆写 `entityInside`）：实体只要与方块相交，每 tick 就会被
 * 通知一次，流体方块同样适用。
 *
 * ## 子类
 *
 * | 方块 | 用途 |
 * | --- | --- |
 * | 本类 | 默认；有影响或什么都没影响的流体都用它 |
 * | [ReactiveLiquidBlock] | 额外做"碰水凝固" |
 *
 * ## 不覆写 codec
 *
 * 与 [ReactiveLiquidBlock] 一致：液体方块只在运行时由流体生成，
 * 不参与数据包里的方块定义，所以沿用 `LiquidBlock` 的 `CODEC` 即可。
 */
open class AddonLiquidBlock(
    fluid: FlowingFluid,
    properties: Properties,
    protected val spec: FluidSpec,
) : LiquidBlock(fluid, properties) {

    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        super.entityInside(state, level, pos, entity)
        FluidContactApplier.apply(spec, level, pos, entity)
    }
}
