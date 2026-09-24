package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.fluid.WaterReactions
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid
import net.neoforged.neoforge.event.EventHooks

/**
 * 会与水发生凝固反应的液体方块。
 *
 * 碰水时把**自己所在的位置**换成产物（产物查 [WaterReactions]），
 * 并且**区分源方块与流动流体**——两者的产物可以不同（例如熔融铁：
 * 源方块 → 铁矿，流动 → 石头）。
 *
 * ## 为什么自己写方块而不是用 NeoForge 的 `FluidInteractionRegistry`
 *
 * NeoForge 的 `FluidInteractionRegistry.canInteract(level, pos)` 只被
 * `LiquidBlock#onPlace` 与 `LiquidBlock#neighborChanged` 调用，而且它遍历的方向是
 * `LiquidBlock.POSSIBLE_FLOW_DIRECTIONS`（**不含 DOWN**，即不检查自己上方）——
 * 官方注释说"向下的那一路请在 `FlowingFluid#spreadTo` 里自己处理"。
 * 结果就是：熔融宝石**上**浇水不会触发，得额外再写一套 `spreadTo` 覆写。
 *
 * 这里改成继承 [LiquidBlock] 自己监听 `onPlace` / `neighborChanged`，
 * **六向全查**，一套代码覆盖所有情况：
 *
 * | 玩家操作 | 触发路径 |
 * | --- | --- |
 * | 熔融金属流到水边 | `onPlace`（新方块落下）+ `neighborChanged`（水那侧变化） |
 * | 熔融金属放在水面上 | `onPlace`，检测到下方是水 |
 * | 往熔融金属上浇水 | 水在其上方落位 → 我们的 `neighborChanged`，检测到上方是水 |
 *
 * 反应后水**保留**（与原版岩浆遇水成黑曜石的行为一致），只消耗我们自己这一格流体。
 *
 * 产物在**反应发生时**才解析（`WaterReactions` 里包了 lambda），
 * 避免 mod 构造阶段碰上游方块条目的 `unbound value` 问题。
 */
class ReactiveLiquidBlock(
    fluid: FlowingFluid,
    properties: Properties,
) : LiquidBlock(fluid, properties) {

    // 不覆写 codec()：沿用 LiquidBlock 的 CODEC。
    // 我们的液体方块只在运行时由流体生成，不参与数据包里的方块定义。

    /** 本方块对应哪个 [cn.xm1221.AnvilCraftFluid.fluid.FluidSpec.name]（≈ 注册名去掉命名空间） */
    private val fluidName: String by lazy { BuiltInRegistries.FLUID.getKey(this.fluid).path }

    override fun onPlace(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        oldState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        reactWithAdjacentWater(level, pos)
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        neighborBlock: Block,
        neighborPos: BlockPos,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston)
        reactWithAdjacentWater(level, pos)
    }

    /** 六向找水；找到就按"源 / 流动"取产物，把自己这格换掉 */
    private fun reactWithAdjacentWater(level: Level, pos: BlockPos) {
        if (level.isClientSide) return

        // 已经被替换过（例如连锁触发）就不再处理
        if (level.getBlockState(pos).block !== this) return
        if (!isTouchingWater(level, pos)) return

        // 关键：源方块与流动流体的产物可以不同
        val state = level.getFluidState(pos)
        val product = if (state.isSource) {
            WaterReactions.sourceProduct(fluidName)
        } else {
            WaterReactions.flowingProduct(fluidName)
        } ?: return

        level.setBlockAndUpdate(
            pos,
            EventHooks.fireFluidPlaceBlockEvent(level, pos, pos, product.defaultBlockState()),
        )
        // 1501 = 水浇岩浆的"嘶——"+ 白烟反馈
        level.levelEvent(1501, pos, 0)
    }

    private fun isTouchingWater(level: Level, pos: BlockPos): Boolean =
        DIRECTIONS.any { level.getFluidState(pos.relative(it)).`is`(FluidTags.WATER) }

    private companion object {
        private val DIRECTIONS: Array<Direction> = Direction.values()
    }
}
