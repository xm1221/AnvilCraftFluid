package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.fluid.FluidPairReactions
import cn.xm1221.AnvilCraftFluid.fluid.FluidPairRule
import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec
import cn.xm1221.AnvilCraftFluid.fluid.WaterReaction
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.FluidState
import net.neoforged.neoforge.event.EventHooks

/**
 * 会和别的流体发生凝固反应的液体方块（同时继承 [AddonLiquidBlock] 的接触影响）。
 *
 * 反应时把**自己所在的位置**换成产物，对手那格保留（与原版岩浆遇水成黑曜石一致）。
 * 对手有两类，判定顺序是"先水、后另一种流体"：
 *
 * | 对手 | 规则来源 | 源 / 流动 |
 * | --- | --- | --- |
 * | 水（`#minecraft:water`） | [WaterReaction] | 两者产物可以不同（熔融铁：源 → 铁矿，流动不反应） |
 * | 表里的另一种流体 | [FluidPairRule] | 每条规则自己声明"我必须是源 / 对手必须是源"，见 [FluidPairReactions] |
 *
 * ## 为什么自己写方块而不是用 NeoForge 的 `FluidInteractionRegistry`
 *
 * NeoForge 的 `FluidInteractionRegistry.canInteract(level, pos)` 只被
 * `LiquidBlock#onPlace` 与 `LiquidBlock#neighborChanged` 调用，而且它遍历的方向是
 * `LiquidBlock.POSSIBLE_FLOW_DIRECTIONS`（**不含 DOWN**，即不检查自己上方）——
 * 官方注释说"向下的那一路请在 `FlowingFluid#spreadTo` 里自己处理"。
 * 结果就是：熔融宝石**上**浇水不会触发，得额外再写一套 `spreadTo` 覆写。
 *
 * 这里改成继承 [AddonLiquidBlock] 自己监听 `onPlace` / `neighborChanged`，
 * **六向全查**，一套代码覆盖所有情况：
 *
 * | 玩家操作 | 触发路径 |
 * | --- | --- |
 * | 熔融金属流到水边 | `onPlace`（新方块落下）+ `neighborChanged`（水那侧变化） |
 * | 熔融金属放在水面上 | `onPlace`，检测到下方是水 |
 * | 往熔融金属上浇水 | 水在其上方落位 → 我们的 `neighborChanged`，检测到上方是水 |
 * | 余烬流体流到浮霜流体边上 | 同上两条路径，只是对手换成查表 |
 *
 * 产物在**反应发生时**才解析（表里包了 lambda），
 * 避免 mod 构造阶段碰上游方块条目的 `unbound value` 问题。
 */
class ReactiveLiquidBlock(
    fluid: FlowingFluid,
    properties: Properties,
    private val reaction: WaterReaction?,
    private val pairRules: List<FluidPairRule>,
    spec: FluidSpec,
) : AddonLiquidBlock(fluid, properties, spec) {

    // 不覆写 codec()：沿用 LiquidBlock 的 CODEC。
    // 我们的液体方块只在运行时由流体生成，不参与数据包里的方块定义。

    override fun onPlace(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        oldState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        react(level, pos)
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
        react(level, pos)
    }

    /** 六向找对手；找到就按规则取产物，把自己这格换掉 */
    private fun react(level: Level, pos: BlockPos) {
        if (level.isClientSide) return

        // 已经被替换过（例如连锁触发）就不再处理
        if (level.getBlockState(pos).block !== this) return

        val product = resolveProduct(level, pos, level.getFluidState(pos)) ?: return
        level.setBlockAndUpdate(
            pos,
            EventHooks.fireFluidPlaceBlockEvent(level, pos, pos, product.defaultBlockState()),
        )
        // 1501 = 水浇岩浆的"嘶——"+ 白烟反馈，两种反应共用
        level.levelEvent(1501, pos, 0)
    }

    /** 命中哪条规则就返回哪个产物；都不命中返回 null */
    private fun resolveProduct(level: Level, pos: BlockPos, state: FluidState): Block? {
        // 关键：区分源方块与流动流体。
        // LiquidBlock.LEVEL 是 0~15，其中 0 = 源方块、1~7 = 流动、8 = 下落，
        // 而 getFluidState(state) 是按 LEVEL 取 stateCache，所以 isSource() 恰好只对 LEVEL 0 为真。
        val selfSource = state.isSource

        // ① 遇水
        if (reaction != null && hasNeighbor(level, pos) { it.`is`(FluidTags.WATER) }) {
            return if (selfSource) reaction.sourceProduct() else reaction.flowingProduct?.invoke()
        }

        // ② 遇表里的另一种流体：按列表顺序，第一条命中即生效
        for (rule in pairRules) {
            val other = neighborFluid(level, pos, rule.other) ?: continue
            if (rule.selfSource != null && rule.selfSource != selfSource) continue
            if (rule.otherSource != null && rule.otherSource != other.isSource) continue
            return rule.product()
        }
        return null
    }

    /** 六向里有没有满足条件的邻居流体 */
    private fun hasNeighbor(level: Level, pos: BlockPos, predicate: (FluidState) -> Boolean): Boolean =
        DIRECTIONS.any { predicate(level.getFluidState(pos.relative(it))) }

    /**
     * 六向里第一格属于 [name] 那种流体的流体状态（源与流动都算）。
     *
     * ⚠️ 按 [FluidSpec.name] 取注册表里的实例来比，**不要**拿液体方块自身的 `fluid` 反推名字：
     * Registrum 建液体方块时传进去的是**流动流体**（`flowing_<name>`），反推会全部落空。
     */
    private fun neighborFluid(level: Level, pos: BlockPos, name: String): FluidState? {
        val handle = AddonFluids.byName(name) ?: return null
        val flowing = handle.fluid.get()
        for (direction in DIRECTIONS) {
            val state = level.getFluidState(pos.relative(direction))
            if (state.isEmpty) continue
            if (state.type === handle.source || state.type === flowing) return state
        }
        return null
    }

    private companion object {
        private val DIRECTIONS: Array<Direction> = Direction.values()
    }
}
