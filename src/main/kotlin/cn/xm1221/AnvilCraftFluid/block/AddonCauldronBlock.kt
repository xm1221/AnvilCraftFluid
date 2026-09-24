package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import com.mojang.serialization.MapCodec
import dev.dubhe.anvilcraft.api.hammer.IHammerRemovable
import dev.dubhe.anvilcraft.block.better.BetterAbstractCauldronBlock
import dev.dubhe.anvilcraft.util.FireReforgingUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.cauldron.CauldronInteraction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
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
 * @param interactionMap 该锅的桶交互表
 * @param reforging 是否是**余烬液体锅**：是的话掉进锅里的物品也会被重铸修复
 *   （对齐上游 `ItemEntityMixin` 里 `LAVA_CAULDRON = 10` 那一档，只是速率取配置值）
 */
class AddonCauldronBlock(
    properties: Properties,
    private val interactionMap: CauldronInteraction.InteractionMap,
    private val reforging: Boolean = false,
) : BetterAbstractCauldronBlock(properties, interactionMap), IHammerRemovable {

    override fun codec(): MapCodec<out AbstractCauldronBlock> =
        simpleCodec { props -> AddonCauldronBlock(props, interactionMap, reforging) }

    override fun getContentHeight(state: BlockState): Double = 0.9375

    override fun isFull(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int = 3

    /**
     * 掉进余烬液体锅里的物品同样被重铸修复（"和原本在熔岩中的修复一样"）。
     *
     * ⚠️ 与 [ReforgingFluidBlock] 一样：**不消耗锅里的流体**，只有 `FIRE_REFORGING` 组件
     * 且已掉耐久的物品会被修；条件不满足时上游工具自己会拒绝。
     */
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        super.entityInside(state, level, pos, entity)
        if (!reforging || level.isClientSide) return
        if (!isEntityInsideContent(state, pos, entity)) return
        val stack = (entity as? ItemEntity)?.item ?: return
        FireReforgingUtil.repair(stack, AnvilCraftFluid.CONFIG.emberRepairPerTick, level, pos)
    }
}

