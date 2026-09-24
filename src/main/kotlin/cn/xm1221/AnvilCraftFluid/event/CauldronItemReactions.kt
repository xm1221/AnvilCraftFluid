package cn.xm1221.AnvilCraftFluid.event

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.dubhe.anvilcraft.api.event.LargeCauldronEvent
import dev.dubhe.anvilcraft.api.fluid.LargeCauldronFluidHandler
import dev.dubhe.anvilcraft.api.itemhandler.LargeCauldronInputHandler
import dev.dubhe.anvilcraft.init.block.ModFluids
import dev.dubhe.anvilcraft.init.item.ModComponents
import dev.dubhe.anvilcraft.util.CompatUtil
import dev.dubhe.anvilcraft.util.FireReforgingUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.material.Fluid
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/**
 * 大型炼药锅上的「流体 × 物品」反应（P1-1 的三个特殊行为）。
 *
 * ## 为什么挂在 [LargeCauldronEvent.ServerTick]
 *
 * 这是**唯一**能同时拿到"锅里有什么流体"和"输入槽里有什么物品"的逐 tick 钩子：
 * `LargeCauldronBlockEntity#serverTick` 在 `isMainPart()` 通过后、每服务器 tick
 * post 一次该事件（post 到 `NeoForge.EVENT_BUS`，所以本监听器注册在游戏总线上）。
 *
 * 拿到锅后全部走公开访问器：
 * - `getFluids()` → [LargeCauldronFluidHandler]（8 罐 × 64 B）：`getFluidInTank` /
 *   `drainStoredFluid` / `fill`
 * - `getInputHandler()` → [LargeCauldronInputHandler]（8 槽）：`mutateStackInSlot(slot, mutator)`
 *   原地改物品（改附魔、修耐久）
 *
 * ## 三个行为
 *
 * | 流体 | 行为 | 参考 |
 * | --- | --- | --- |
 * | 余烬液体 | 给带 `FIRE_REFORGING` 的余烬装备加速修复，按耐久耗流体 | 上游岩浆自修复 `LAVA_REPAIR_PER_TICK = 10` 且不耗流体 |
 * | 熔融金 | 洗掉诅咒附魔，**按同量产出诅咒金液体** | 上游 `RoyalGrindstoneMenu.GOLD_PER_CURSE = 16` 的祛咒 |
 * | 浮霜液体 | 洗掉**全部**附魔 | 上游余烬砂轮"附魔转移"的反向操作 |
 *
 * 三者互不干扰：各自只在锅里存在自己那种流体时才动手，且处理的是不同效果。
 */
object CauldronItemReactions {

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
        reforgeWithEmber(event.serverLevel, event.cauldron.blockPos, fluids, input)
        washCursesWithGold(fluids, input)
        washEnchantmentsWithFrost(fluids, input)
    }

    // ───────────────────────── 余烬液体：加速余烬装备修复 ─────────────────────────

    private fun reforgeWithEmber(
        level: ServerLevel,
        cauldronPos: BlockPos,
        fluids: LargeCauldronFluidHandler,
        input: LargeCauldronInputHandler,
    ) {
        val ember = AddonFluids.byName("ember_fluid")?.source ?: return
        var budget = fluids.amountOf(ember)
        if (budget <= 0) return

        val perDurability = AnvilCraftFluid.CONFIG.emberFluidPerDurability.coerceAtLeast(1)
        val perTick = AnvilCraftFluid.CONFIG.emberRepairPerTick.coerceAtLeast(1)

        for (slot in 0 until input.slots) {
            if (budget < perDurability) break
            // 这点流体最多能修多少耐久
            val affordable = minOf(perTick, budget / perDurability)
            if (affordable <= 0) break

            var repaired = 0
            input.mutateStackInSlot(slot) { stack ->
                if (stack.has(ModComponents.FIRE_REFORGING) && stack.isDamaged) {
                    val amount = minOf(affordable, stack.damageValue)
                    // 上游工具：内部会再校验组件与耐久，并触发重铸统计
                    repaired = if (amount > 0 && FireReforgingUtil.repair(stack, amount, level, cauldronPos)) {
                        amount
                    } else {
                        0
                    }
                } else {
                    repaired = 0
                }
                repaired > 0
            }
            if (repaired > 0) {
                budget -= fluids.consume(ember, repaired * perDurability)
                // 修满时打一条（每 tick 修复都会走到上面，打日志会刷屏）
                if (input.getStackInSlot(slot).damageValue == 0) {
                    AnvilCraftFluid.LOGGER.debug(
                        "Ember reforge: repaired item in slot {} back to full, {} mB ember fluid left",
                        slot,
                        budget,
                    )
                }
            }
        }
    }

    // ───────────────────────── 熔融金：洗诅咒 → 诅咒金液体 ─────────────────────────

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
                removed = if (washable > 0) removeEnchantments(stack, washable, onlyCurses = true) else 0
                removed > 0
            }
            if (removed <= 0) continue

            val cost = removed * perCurse
            available -= fluids.consume(gold, cost)
            // 洗下来的诅咒被流体吸收：等量熔融金变成诅咒金液体
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

    // ───────────────────────── 浮霜液体：洗掉全部附魔 ─────────────────────────

    private fun washEnchantmentsWithFrost(
        fluids: LargeCauldronFluidHandler,
        input: LargeCauldronInputHandler,
    ) {
        val frost = AddonFluids.byName("frost_fluid")?.source ?: return
        val perEnchantment = AnvilCraftFluid.CONFIG.frostFluidPerEnchantment.coerceAtLeast(1)
        // 洗下来的附魔变成上游的"液态附魔"（不挂具体魔咒组件）
        val liquidEnchantment = ModFluids.LIQUID_ENCHANTMENT.get()

        var available = fluids.amountOf(frost)
        if (available < perEnchantment) return

        for (slot in 0 until input.slots) {
            if (available < perEnchantment) break

            var removed = 0
            input.mutateStackInSlot(slot) { stack ->
                val enchantments = countEnchantments(stack, onlyCurses = false)
                val washable = minOf(enchantments, available / perEnchantment)
                removed = if (washable > 0) removeEnchantments(stack, washable, onlyCurses = false) else 0
                removed > 0
            }
            if (removed <= 0) continue

            val cost = removed * perEnchantment
            available -= fluids.consume(frost, cost)
            // 洗下来的附魔按同量变成液态附魔
            fluids.fill(FluidStack(liquidEnchantment, cost), IFluidHandler.FluidAction.EXECUTE)
            turnEmptyEnchantedBookIntoBook(input, slot)
            AnvilCraftFluid.LOGGER.debug(
                "Frost wash: removed {} enchantment(s) in slot {}, consumed {} mB frost fluid " +
                    "and produced {} mB liquid enchantment",
                removed,
                slot,
                cost,
                cost,
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
     * 从物品上摘掉最多 [amount] 条附魔，返回实际摘掉的条数。
     *
     * 照上游 `TranscendenceGrindstoneMenu#removeCurses` 的写法：
     * 遍历不可变的 `getOrDefault(...)`，在 `ItemEnchantments.Mutable` 副本上删，
     * 最后 `set` 回去。
     */
    private fun removeEnchantments(stack: ItemStack, amount: Int, onlyCurses: Boolean): Int {
        var removed = 0
        for (type in ENCHANTMENT_TYPES) {
            if (removed >= amount) break
            val current = stack.getOrDefault(type, ItemEnchantments.EMPTY)
            if (current.isEmpty) continue

            val mutable = ItemEnchantments.Mutable(current)
            var changed = false
            for (enchantment in current.keySet()) {
                if (removed >= amount) break
                if (onlyCurses && !enchantment.`is`(EnchantmentTags.CURSE)) continue
                mutable.removeIf { it == enchantment }
                removed++
                changed = true
            }
            if (changed) stack.set(type, mutable.toImmutable())
        }
        return removed
    }
}
