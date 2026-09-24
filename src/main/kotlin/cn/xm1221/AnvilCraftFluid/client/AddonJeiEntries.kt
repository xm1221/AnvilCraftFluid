package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.event.CauldronItemReactions
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.dubhe.anvilcraft.init.block.ModBlocks
import dev.dubhe.anvilcraft.init.block.ModFluids
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.neoforged.neoforge.fluids.FluidStack
import net.minecraft.client.Minecraft

/**
 * JEI 展示用的数据（**只在客户端**加载，JEI 不在时这个文件不会被碰）。
 *
 * 这里的内容纯粹是"说明书"：
 * - [pseudoRecipes]：两条展示用伪配方（浮霜洗附魔、熔融金洗诅咒），
 *   实际行为由 [CauldronItemReactions] 的代码反应完成，配方不参与任何合成。
 * - [emberInfo]：余烬液体不是配方行为（是"站着就修"的方块接触行为），
 *   所以用 JEI 的物品信息页说明。
 *
 * 数值一律**读配置**（`AnvilCraftFluid.CONFIG`），不写死，免得配置改了 JEI 还是旧数字。
 */
object AddonJeiEntries {

    /** 分类图标：浮霜桶；万一取不到就退回大型炼药锅（反应发生的机器） */
    fun iconStack(): ItemStack =
        AddonFluids.byName(FROST)?.bucket?.let(::ItemStack)
            ?: ItemStack(ModBlocks.LARGE_CAULDRON)

    /** 余烬液体的信息页展示物品（余烬桶） */
    fun emberInfoStack(): ItemStack? = AddonFluids.byName(EMBER)?.bucket?.let(::ItemStack)

    /** 余烬液体的信息页文本 */
    fun emberInfo(): Component = Component.translatable("gui.anvilcraft_fluid.info.$EMBER")

    /**
     * 展示用伪配方。
     *
     * 示例物品选"附魔书"，因为它同时能表达两种行为（普通附魔 / 诅咒），
     * 且输出可以明确写成"普通书"——正好对上"洗空的附魔书变回普通书"这条口径。
     * 输入物品写的是**其中一个代表**：实际反应对**任意**带附魔的物品都生效。
     */
    fun pseudoRecipes(): List<CauldronReactionJeiRecipe> = buildList {
        val frost = AddonFluids.byName(FROST)?.source
        val gold = AddonFluids.byName(MOLTEN_GOLD)?.source
        val cursedGold = AddonFluids.byName(CURSED_GOLD)?.source
        // 上游的"液态魔咒"；洗下来的附魔按它的等级公式变成这个流体
        val liquidEnchantment = ModFluids.LIQUID_ENCHANTMENT.get()

        if (frost != null) {
            // 锋利 V → 2^(5-1) = 16 mB
            val level = 5
            add(
                CauldronReactionJeiRecipe(
                    inputItem = exampleEnchantedBook(Enchantments.SHARPNESS, level),
                    inputFluid = FluidStack(frost, AnvilCraftFluid.CONFIG.frostFluidPerEnchantment),
                    outputItem = ItemStack(Items.BOOK),
                    outputFluid = FluidStack(
                        liquidEnchantment,
                        CauldronItemReactions.liquidAmountFor(level),
                    ),
                    notes = listOf(
                        Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.frost.0"),
                        Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.frost.1"),
                    ),
                ),
            )
        }

        if (gold != null && cursedGold != null) {
            val perCurse = AnvilCraftFluid.CONFIG.goldFluidPerCurse
            add(
                CauldronReactionJeiRecipe(
                    inputItem = exampleEnchantedBook(Enchantments.BINDING_CURSE, 1),
                    inputFluid = FluidStack(gold, perCurse),
                    outputItem = ItemStack(Items.BOOK),
                    outputFluid = FluidStack(cursedGold, perCurse),
                    notes = listOf(
                        Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.curse.0"),
                        Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.curse.1"),
                    ),
                ),
            )
        }
    }

    /**
     * 造一本"带指定附魔的附魔书"当示例物品。
     *
     * 附魔是注册表内容，只能在客户端连着世界之后取（JEI 注册配方时通常已经连上了）。
     * 取不到就退回一本**未附魔**的附魔书——显示略糙，但不会崩。
     */
    private fun exampleEnchantedBook(key: ResourceKey<Enchantment>, level: Int): ItemStack {
        val stack = ItemStack(Items.ENCHANTED_BOOK)
        val connection = Minecraft.getInstance().connection ?: return stack
        val holder = connection.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(key)
            .orElse(null) ?: return stack

        val enchantments = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
        enchantments.set(holder, level)
        stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable())
        return stack
    }

    private const val FROST = "frost_fluid"
    private const val EMBER = "ember_fluid"
    private const val MOLTEN_GOLD = "molten_gold"
    private const val CURSED_GOLD = "cursed_gold_fluid"
}
