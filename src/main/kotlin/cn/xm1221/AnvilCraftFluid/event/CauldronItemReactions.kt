package cn.xm1221.AnvilCraftFluid.event

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.dubhe.anvilcraft.api.event.LargeCauldronEvent
import dev.dubhe.anvilcraft.api.fluid.LargeCauldronFluidHandler
import dev.dubhe.anvilcraft.api.itemhandler.LargeCauldronInputHandler
import dev.dubhe.anvilcraft.init.block.ModFluids
import dev.dubhe.anvilcraft.util.CompatUtil
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.material.Fluid
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/**
 * 大型炼药锅上的「流体 × 物品」反应。
 *
 * ## 为什么挂在 [LargeCauldronEvent.ServerTick]
 *
 * 这是**唯一**能同时拿到"锅里有什么流体"和"输入槽里有什么物品"的逐 tick 钩子：
 * `LargeCauldronBlockEntity#serverTick` 在 `isMainPart()` 通过后、每服务器 tick
 * post 一次该事件（post 到 `NeoForge.EVENT_BUS`，所以本监听器注册在游戏总线上）。
 *
 * 拿到锅后全部走公开访问器：
 * - `getFluids()` → [LargeCauldronFluidHandler]（8 罐 × 64000 mB）：`getFluidInTank` /
 *   `drainStoredFluid` / `fill`
 * - `getInputHandler()` → [LargeCauldronInputHandler]（8 槽）：`mutateStackInSlot(slot, mutator)`
 *   原地改物品（改附魔、修耐久）；谓词返回 true 才会写回
 *
 * ## 行为
 *
 * | 流体 | 行为 | 参考 |
 * | --- | --- | --- |
 * | 熔融金 | 洗掉诅咒附魔，**按同量产出诅咒金流体** | 上游 `RoyalGrindstoneMenu.GOLD_PER_CURSE = 16` 的祛咒 |
 * | 浮霜流体 | 洗掉**全部**附魔，产出**液态魔咒**（量按 `2^(等级-1)` mB，见 [liquidAmountFor]） | 上游 `TranscendenceGrindstoneMenu#getLiquidAmount` |
 *
 * ⚠️ **余烬流体不在这里**（用户口径）：它的修复与上游"站在火/岩浆里就修"一样，
 * 是**方块接触**行为而不是炼药锅行为，见 `block/AddonLiquidBlock` 与 `block/AddonCauldronBlock`
 * （两者共用 `fluid/FluidContactApplier`）。
 *
 * ⚠️ 这两个行为是**代码反应**，不是数据配方。查过上游："祛除附魔 → 液态魔咒"上游自己也
 * 不是用配方做的（超凡砂轮 GUI + `LiquidEnchantmentCauldronRecipe` 硬编码代码反应，
 * JEI 里那两条 `liquid_enchantment_cleanse/assimilation` 只是手工补的展示用假配方），
 * 而 `solid_liquid` 配方的 result 必须是**具体物品**，表达不了"任意附魔物品 → 它自己去附魔"。
 */
object CauldronItemReactions {

    /**
     * [liquidAmountFor] 的最大移位位数：`2^16 = 65536 mB`。
     *
     * 上游 `TranscendenceGrindstoneMenu#getLiquidAmount` 是 `1L << (level-1)` 且只挡
     * `level >= 64`；我们用 `Int` 装 mB，所以自己封顶，避免魔改的高等级魔咒算出溢出量。
     */
    private const val MAX_LIQUID_ENCHANTMENT_SHIFT: Int = 16

    /**
     * 物品上可能承载附魔的全部组件（对齐上游 `TranscendenceGrindstoneMenu#getEnchantmentTypes`）。
     *
     * ⚠️ **必须 lazy**：`CompatUtil` 的静态初始化里有 `ModBlocks.END_DUST.getDefaultState()`
     * 这类调用，只有在方块注册表填充之后才安全。本 object 在 mod 构造阶段就被
     * `NeoForge.EVENT_BUS.register(...)` 触碰，如果这里不是懒加载，会直接
     * `NullPointerException: Trying to access unbound value: anvilcraft:end_dust`。
     */
    private val ENCHANTMENT_TYPES: List<DataComponentType<ItemEnchantments>> by lazy {
        buildList {
            add(DataComponents.ENCHANTMENTS)
            add(DataComponents.STORED_ENCHANTMENTS)
            addAll(CompatUtil.ENCHANTMENTS_TYPES)
        }
    }

    /**
     * 首次收到事件时打一条日志，用来确认**钩子本身活着**。
     *
     * 这三个行为只在世界里发生，没有别的可观测证据；用户实机反馈"行为不正常"时，
     * 先看日志里有没有这一行，就能区分"钩子没挂上"和"钩子挂了但条件没命中"。
     */
    private var hookAliveLogged = false

    @SubscribeEvent
    fun onLargeCauldronTick(event: LargeCauldronEvent.ServerTick) {
        val fluids = event.cauldron.fluids

        if (!hookAliveLogged) {
            hookAliveLogged = true
            AnvilCraftFluid.LOGGER.debug(
                "LargeCauldronEvent.ServerTick hook alive — fluid×item reactions are active " +
                    "(cauldron at {}, {} mB stored)",
                event.cauldron.blockPos,
                fluids.totalAmount,
            )
        }

        // 空锅直接跳过，省掉后面所有查找
        if (fluids.totalAmount <= 0) return

        val input = event.cauldron.inputHandler
        // 注意：BlockEntity.worldPosition 是 protected 字段，Kotlin 下要用 getBlockPos()
        washCursesWithGold(fluids, input)
        washEnchantmentsWithFrost(fluids, input)
    }

    // ───────────────────────── 熔融金：洗诅咒 → 诅咒金流体 ─────────────────────────

    private fun washCursesWithGold(
        fluids: LargeCauldronFluidHandler,
        input: LargeCauldronInputHandler,
    ) {
        val gold = AddonFluids.byName("molten_gold")?.source ?: return
        val cursedGold = AddonFluids.byName("cursed_gold_fluid")?.source ?: return
        val perCurse = AnvilCraftFluid.CONFIG.goldFluidPerCurse.coerceAtLeast(1)

        var available = fluids.amountOf(gold)
        if (available < perCurse) return

        for (slot in 0 until input.slots) {
            if (available < perCurse) break

            var removed = 0
            input.mutateStackInSlot(slot) { stack ->
                val curses = countEnchantments(stack, onlyCurses = true)
                val washable = minOf(curses, available / perCurse)
                // 诅咒走熔融金这条路，产出的是诅咒金流体，所以只用"洗掉几条"，不看液态魔咒量
                removed = if (washable > 0) {
                    removeEnchantments(stack, washable, onlyCurses = true).count
                } else {
                    0
                }
                removed > 0
            }
            if (removed <= 0) continue

            val cost = removed * perCurse
            available -= fluids.consume(gold, cost)
            // 洗下来的诅咒被流体吸收：等量熔融金变成诅咒金流体
            fluids.fill(FluidStack(cursedGold, cost), IFluidHandler.FluidAction.EXECUTE)
            turnEmptyEnchantedBookIntoBook(input, slot)
            AnvilCraftFluid.LOGGER.debug(
                "Melted-gold wash: removed {} curse enchantment(s) in slot {}, consumed {} mB molten gold",
                removed,
                slot,
                cost,
            )
        }
    }

    // ───────────────────────── 浮霜流体：洗掉全部附魔 ─────────────────────────

    /**
     * 一条附魔值多少液态魔咒。
     *
     * 口径照抄上游 `TranscendenceGrindstoneMenu#getLiquidAmount`：**`2^(等级-1)` mB**
     * （1 级 = 1、2 级 = 2、3 级 = 4、4 级 = 8、5 级 = 16……）——等级越高，洗出来的液态魔咒越多。
     * 这正是用户说的"液态魔咒若干"。
     *
     * ⚠️ 魔改魔咒可能有很高的 `maxLevel`，必须封顶：这里最多 `2^16 = 65536 mB`
     * （大型炼药锅总容量 8 × 64000 = 512000 mB），免得算出天文数字或移位溢出。
     *
     * 公开给 JEI 展示用（`client/AddonJeiEntries`），保证显示的数值与实际逻辑同一个来源。
     */
    fun liquidAmountFor(level: Int): Int {
        if (level <= 0) return 0
        return 1 shl minOf(level - 1, MAX_LIQUID_ENCHANTMENT_SHIFT)
    }

    /** 一次洗附魔的结果：洗掉几条 + 一共产出多少 mB 液态魔咒 */
    private data class WashResult(val count: Int, val fluidMb: Int) {
        companion object {
            val NONE = WashResult(0, 0)
        }
    }

    private fun washEnchantmentsWithFrost(
        fluids: LargeCauldronFluidHandler,
        input: LargeCauldronInputHandler,
    ) {
        val frost = AddonFluids.byName("frost_fluid")?.source ?: return
        val perEnchantment = AnvilCraftFluid.CONFIG.frostFluidPerEnchantment.coerceAtLeast(1)
        // 洗下来的附魔变成上游的"液态魔咒"（按用户口径不挂具体魔咒组件）
        val liquidEnchantment = ModFluids.LIQUID_ENCHANTMENT.get()

        var available = fluids.amountOf(frost)
        if (available < perEnchantment) return

        for (slot in 0 until input.slots) {
            if (available < perEnchantment) break

            var washed = WashResult.NONE
            input.mutateStackInSlot(slot) { stack ->
                val enchantments = countEnchantments(stack, onlyCurses = false)
                val washable = minOf(enchantments, available / perEnchantment)
                washed = if (washable > 0) {
                    removeEnchantments(stack, washable, onlyCurses = false)
                } else {
                    WashResult.NONE
                }
                washed.count > 0
            }
            if (washed.count <= 0) continue

            // 扣量按"每条附魔"（浮霜配置值），产出量按上游的等级公式
            val cost = washed.count * perEnchantment
            available -= fluids.consume(frost, cost)
            fluids.fill(FluidStack(liquidEnchantment, washed.fluidMb), IFluidHandler.FluidAction.EXECUTE)
            turnEmptyEnchantedBookIntoBook(input, slot)
            AnvilCraftFluid.LOGGER.debug(
                "Frost wash: removed {} enchantment(s) in slot {}, consumed {} mB frost fluid " +
                    "and produced {} mB liquid enchantment",
                washed.count,
                slot,
                cost,
                washed.fluidMb,
            )
        }
    }

    /**
     * 被洗空的附魔书变回普通书。
     *
     * `mutateStackInSlot` 只能原地改物品（改不了物品**类型**），所以只能事后走
     * `setStackInSlot`；而它遇到"跨槽重复物品"会抛 `IllegalArgumentException`，
     * 因此先用 `isItemValid` 问一句——别的槽已经有普通书时就保持原样，不冒崩的风险。
     */
    private fun turnEmptyEnchantedBookIntoBook(input: LargeCauldronInputHandler, slot: Int) {
        val stack = input.getStackInSlot(slot)
        if (!stack.`is`(Items.ENCHANTED_BOOK)) return
        if (countEnchantments(stack, onlyCurses = false) > 0) return

        val book = ItemStack(Items.BOOK, stack.count)
        if (input.isItemValid(slot, book)) input.setStackInSlot(slot, book)
    }

    // ───────────────────────────────── 工具 ─────────────────────────────────

    /** 锅里某种流体的总存量（mB） */
    private fun LargeCauldronFluidHandler.amountOf(fluid: Fluid): Int {
        var total = 0
        for (tank in 0 until tanks) {
            val stored = getFluidInTank(tank)
            if (!stored.isEmpty && stored.`is`(fluid)) total += stored.amount
        }
        return total
    }

    /** 扣掉某种流体，返回实际扣掉的 mB */
    private fun LargeCauldronFluidHandler.consume(fluid: Fluid, mb: Int): Int {
        if (mb <= 0) return 0
        val drained = drainStoredFluid(FluidStack(fluid, mb), IFluidHandler.FluidAction.EXECUTE)
        return drained.amount
    }

    /** 统计物品上的附魔条数；[onlyCurses] 为 true 时只数 `#minecraft:curse` */
    private fun countEnchantments(stack: ItemStack, onlyCurses: Boolean): Int {
        var count = 0
        for (type in ENCHANTMENT_TYPES) {
            val enchantments = stack.getOrDefault(type, ItemEnchantments.EMPTY)
            if (enchantments.isEmpty) continue
            for (enchantment in enchantments.keySet()) {
                if (!onlyCurses || enchantment.`is`(EnchantmentTags.CURSE)) count++
            }
        }
        return count
    }

    /**
     * 从物品上摘掉最多 [amount] 条附魔。
     *
     * 照上游 `TranscendenceGrindstoneMenu#removeCurses` 的写法：
     * 遍历不可变的 `getOrDefault(...)`，在 `ItemEnchantments.Mutable` 副本上删，
     * 最后 `set` 回去。
     *
     * 返回 [WashResult]：既给出**摘掉几条**（用来算浮霜耗量），也给出这些附魔
     * 一共值多少 **mB 液态魔咒**（按 [liquidAmountFor] 的上游等级公式算）。
     *
     * ⚠️ 只能按"条"删，删不掉"某条附魔的一部分等级"——`RecipeResult` 与
     * `ItemEnchantments.Mutable` 都没有那种操作。所以洗掉一条 5 级附魔 = 整条没了，
     * 产出按它的等级算。
     */
    private fun removeEnchantments(stack: ItemStack, amount: Int, onlyCurses: Boolean): WashResult {
        var removed = 0
        var fluidMb = 0
        for (type in ENCHANTMENT_TYPES) {
            if (removed >= amount) break
            val current = stack.getOrDefault(type, ItemEnchantments.EMPTY)
            if (current.isEmpty) continue

            val mutable = ItemEnchantments.Mutable(current)
            var changed = false
            for (enchantment in current.keySet()) {
                if (removed >= amount) break
                if (onlyCurses && !enchantment.`is`(EnchantmentTags.CURSE)) continue
                fluidMb += liquidAmountFor(current.getLevel(enchantment))
                mutable.removeIf { it == enchantment }
                removed++
                changed = true
            }
            if (changed) stack.set(type, mutable.toImmutable())
        }
        return WashResult(removed, fluidMb)
    }
}
