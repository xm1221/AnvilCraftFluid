package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import dev.dubhe.anvilcraft.util.FireReforgingUtil
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid

/**
 * 接触即修复物品的流体方块（目前只有**余烬液体**用）。
 *
 * ## 设计口径（用户拍板）
 *
 * "余烬液体的修复和原本在熔岩中的修复一样，只是快一些，**和炼药锅无关**。"
 *
 * 上游的重铸修复（`ItemEntityMixin#fireReforging`）是一张"站在哪个方块里就每 tick 修多少"的表：
 *
 * | 方块 | 每 tick 修复 |
 * | --- | --- |
 * | 火 | 2 |
 * | 灵魂火 | 5 |
 * | 岩浆 / 岩浆炼药锅 | 10 |
 *
 * 也就是说它**根本不消耗任何燃料**，纯粹是方块接触判定，而且对**世界里掉落的物品实体**同样生效。
 * 所以这里不去挂大型炼药锅的逐 tick 钩子，而是照同样的方式做：物品站在余烬液体里就被修，
 * 速率取 `ember_repair_per_tick`（默认 20，即岩浆的两倍——"只是快一些"），不耗流体。
 *
 * 为什么用 [LiquidBlock#entityInside] 而不是 mixin：原版与上游都是这么做的
 * （上游的 `ExpFluidBlock`、`LavaCauldronBlock` 都覆写 `entityInside`），
 * 实体每 tick 都在方块的 `entityInside` 里被通知一次，流体方块同样适用，无需 mixin。
 */
class ReforgingFluidBlock(
    fluid: FlowingFluid,
    properties: Properties,
) : LiquidBlock(fluid, properties) {

    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        super.entityInside(state, level, pos, entity)
        // 只在服务端动物品：客户端也跑一遍会造成无意义的本地改动
        if (level.isClientSide) return
        val stack = (entity as? ItemEntity)?.item ?: return
        // 上游工具内部会自行校验 FIRE_REFORGING 组件与耐久，不满足条件时什么也不做
        FireReforgingUtil.repair(stack, AnvilCraftFluid.CONFIG.emberRepairPerTick, level, pos)
    }
}
