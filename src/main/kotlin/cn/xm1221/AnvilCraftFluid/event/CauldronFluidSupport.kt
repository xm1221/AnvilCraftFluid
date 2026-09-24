package cn.xm1221.AnvilCraftFluid.event

import dev.anvilcraft.lib.v2.recipe.cache.BlockCache
import dev.dubhe.anvilcraft.api.block.IIgnitableCauldron
import dev.dubhe.anvilcraft.api.fluid.IFluidHandlerHolder
import dev.dubhe.anvilcraft.recipe.anvil.predicate.block.HasCauldron
import dev.dubhe.anvilcraft.recipe.anvil.util.WrapUtils
import dev.dubhe.anvilcraft.recipe.component.HasCauldronSimple
import dev.dubhe.anvilcraft.util.CauldronUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import kotlin.math.roundToInt

/**
 * 炼药锅 / 容器流体条件的判定与执行，语义对齐 AnvilCraft 的
 * `HasCauldron`（`test` / `accept` / `applyFluid` / `applyEmpty`）。
 *
 * 移植自参考工程 AnvilCraft-Wither（同一作者、同版本 API 栈），
 * 用于**单方块炼药锅 + [BlockCache]** 这条路径——即自己手写配方匹配、
 * 或实现"锅里有某流体时对物品做什么"的行为时使用
 * （P1 的浮霜洗附魔 / 金流体洗诅咒 / 余烬流体加速修复会用到）。
 *
 * > 注意：**大型炼药锅**（`LargeCauldronBlockEntity`）不需要本类——
 * > 它直接暴露 `getFluids()`（真 `IFluidHandler`）与
 * > `getInputHandler().mutateStackInSlot(...)`，配合 `LargeCauldronEvent.ServerTick` 即可。
 *
 * 流体来源优先级（与 AnvilCraft 一致）：
 * 1. [IFluidHandlerHolder]`#getFluidHandler()`：鱼缸、大锅、储液罐等；
 * 2. [IIgnitableCauldron]`#getFluid()`：熔融宝石锅、火焰锅等锅类；
 * 3. 方块 → 流体映射（[WrapUtils.cauldron2Fluid]）+ 方块状态 LEVEL 属性：
 *    原版水锅 / 岩浆锅 / 细雪锅，以及**本模组登记进 `CauldronFluidContent` 的满锅**。
 *
 * 注意事项：
 * - [BlockCache] 是缓存，`setBlock` 只写缓存，必须调用 `BlockCache.accept()` 才会落到世界；
 * - 无流体字段的配方其流体谓词为 `amount(0)`，语义是「要求空锅」，
 *   所以锅 / 鱼缸里已有流体时普通时移配方不会匹配（与 AnvilCraft 行为一致）。
 */
object CauldronFluidSupport {

    /** 取容器的流体处理器（鱼缸、大锅、储液罐等 [IFluidHandlerHolder]） */
    private fun fluidHandler(cache: BlockCache, pos: BlockPos): IFluidHandler? =
        (cache.getBlockEntity(pos) as? IFluidHandlerHolder)?.fluidHandler

    /** 当前流体量（mB）：handler 优先，否则按方块状态 LEVEL 属性换算 */
    fun currentAmount(cache: BlockCache, pos: BlockPos): Double {
        fluidHandler(cache, pos)?.let { return it.getFluidInTank(0).amount.toDouble() }
        val state = cache.getBlockState(pos)
        if (state.`is`(Blocks.CAULDRON)) return 0.0
        var property: IntegerProperty = CauldronUtil.LEVEL_4
        var value = state.getOptionalValue(property)
        if (value.isEmpty) {
            property = CauldronUtil.LEVEL_3
            value = state.getOptionalValue(property)
        }
        // 注意：IntegerProperty.max 是 private（AnvilCraft 靠自身 AT 访问），这里用 possibleValues 取上限
        val max = property.possibleValues.maxOrNull() ?: 1
        return value.map { it.toDouble() / max * 1000.0 }.orElse(1000.0)
    }

    /** 当前流体栈 */
    fun currentFluid(cache: BlockCache, pos: BlockPos): FluidStack {
        fluidHandler(cache, pos)?.let { return it.getFluidInTank(0) }
        val state = cache.getBlockState(pos)
        val block = state.block
        val fluid = if (block is IIgnitableCauldron) {
            block.getFluid(cache, pos)
        } else {
            BuiltInRegistries.FLUID.get(WrapUtils.cauldron2Fluid(block))
        }
        return FluidStack(fluid, currentAmount(cache, pos).roundToInt())
    }

    /** 是否存在流体检查条件（对齐 `HasCauldron#hasCheck`） */
    private fun hasCheck(c: HasCauldronSimple): Boolean =
        c.fluid().fluids().isPresent ||
            c.fluid().component().map { !it.patch().isEmpty || it.isNegate }.orElse(false) ||
            c.fluid().amount().isPresent ||
            c.fluid().isNegate

    /**
     * 判定容器是否满足配方的锅 / 流体条件（对齐 `HasCauldron#test` 的常规分支，
     * 不含大锅与实体锅的特殊路径）。
     */
    fun matches(cache: BlockCache, pos: BlockPos, c: HasCauldronSimple): Boolean {
        if (c.chance() < 0f || c.chance() > 1f) return false
        // 声明消耗量超过谓词允许上限 → 否决
        if (c.fluid().amount().flatMap { it.max() }.map { c.consume() > it }.orElse(false)) return false

        val state = cache.getBlockState(pos)
        if (!state.`is`(BlockTags.CAULDRONS)) return false

        val capacity = HasCauldron.getCapacity(cache, pos)
        val produce = c.transforms().sumOf { it.amount }
        if (c.consume() > capacity || produce > capacity) return false

        val curFluid = currentFluid(cache, pos)
        if (hasCheck(c) && !c.fluid().test(curFluid)) return false

        if (c.ignited()) {
            val block = state.block
            if (block !is IIgnitableCauldron || !block.isIgnited(cache, pos)) return false
        }

        if (c.consume() == 0 && produce == 0) return true

        val cur = currentAmount(cache, pos)
        if (c.consume() > cur) return false
        val afterConsume = cur - c.consume()
        if (afterConsume + produce > capacity) return false

        // 锅中有流体、转换有效、前后流体不同、且没消耗完 → 否决
        if (cur > 0 &&
            c.transforms().isNotEmpty() &&
            !FluidStack.isSameFluidSameComponents(curFluid, c.transforms().first()) &&
            afterConsume != 0.0
        ) {
            return false
        }
        return true
    }

    /** 执行流体消耗 / 产出（对齐 `HasCauldron#accept`，方块改动需 `BlockCache.accept()` 提交） */
    fun apply(cache: BlockCache, pos: BlockPos, c: HasCauldronSimple, random: RandomSource) {
        if (random.nextFloat() > c.chance()) return
        if (c.consume() == 0 && c.transforms().isEmpty()) return

        var ignited = c.ignited()
        val state = cache.getBlockState(pos)
        val block = state.block
        if (block is IIgnitableCauldron && block.isIgnited(cache, pos)) ignited = true

        val curFluid = currentFluid(cache, pos)
        val cur = currentAmount(cache, pos)
        val amount = cur - c.consume() + c.transforms().sumOf { it.amount }
        val newFluid = c.transforms().firstOrNull() ?: curFluid

        if (amount > 0 && !newFluid.isEmpty) {
            applyFluid(cache, pos, newFluid, amount, ignited)
        } else {
            applyEmpty(cache, pos)
        }
    }

    private fun applyEmpty(cache: BlockCache, pos: BlockPos) {
        val handler = fluidHandler(cache, pos)
        if (handler != null) {
            handler.drain(Int.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE)
        } else {
            cache.setBlock(pos, Blocks.CAULDRON)
        }
    }

    private fun applyFluid(
        cache: BlockCache,
        pos: BlockPos,
        fluid: FluidStack,
        mb: Double,
        ignitedIn: Boolean,
    ) {
        var ignited = ignitedIn
        val beforeBlock = cache.getBlockState(pos).block
        if (beforeBlock is IIgnitableCauldron && beforeBlock.isIgnited(cache, pos)) ignited = true

        val handler = fluidHandler(cache, pos)
        if (handler != null) {
            val inTank = handler.getFluidInTank(0)
            if (FluidStack.isSameFluidSameComponents(inTank, fluid)) {
                val diff = mb.roundToInt() - inTank.amount
                if (diff < 0) {
                    handler.drain(-diff, IFluidHandler.FluidAction.EXECUTE)
                } else {
                    handler.fill(fluid.copyWithAmount(diff), IFluidHandler.FluidAction.EXECUTE)
                }
            } else {
                handler.drain(Int.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE)
                handler.fill(fluid.copyWithAmount(mb.roundToInt()), IFluidHandler.FluidAction.EXECUTE)
            }
        } else {
            var cauldron = HasCauldron.getDefaultCauldron(fluid.fluid).defaultBlockState()
            var property: IntegerProperty? = CauldronUtil.LEVEL_4
            if (cauldron.getOptionalValue(property).isEmpty) property = CauldronUtil.LEVEL_3
            if (property != null && cauldron.getOptionalValue(property).isEmpty) property = null
            if (property != null) {
                val max = property.possibleValues.maxOrNull() ?: 1
                val layer = (mb / 1000 * max).roundToInt()
                cauldron = if (layer == 0) {
                    Blocks.CAULDRON.defaultBlockState()
                } else {
                    cauldron.setValue(property, layer)
                }
            }
            cache.setBlock(pos, cauldron)
        }

        val afterBlock = cache.getBlockState(pos).block
        if (afterBlock is IIgnitableCauldron && afterBlock.isIgnited(cache, pos) != ignited) {
            afterBlock.setIgnited(cache, pos, ignited)
        }
    }
}
