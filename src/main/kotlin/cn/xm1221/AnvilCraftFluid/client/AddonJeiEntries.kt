package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.event.CauldronItemReactions
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe
import dev.anvilcraft.lib.v2.util.predicate.ItemIngredientPredicate
import dev.dubhe.anvilcraft.init.block.ModBlocks
import dev.dubhe.anvilcraft.init.block.ModFluids
import dev.dubhe.anvilcraft.recipe.FluidMixingRecipe
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient

/**
 * JEI 展示用的**伪配方**数据（只在客户端加载；JEI 不在时这个文件不会被碰）。
 *
 * ## 为什么是伪配方
 *
 * 浮霜洗附魔 / 熔融金洗诅咒是**代码反应**（[CauldronItemReactions]），不是数据配方：
 * 它们要实现"**任意**附魔物品 → 它自己去附魔 + 按附魔条数产出"，而
 * `RecipeResult.result` 必须是具体 `Item`，配方体系表达不了。
 *
 * 上游 AnvilCraft 对同类代码反应的处理就是**手工往 JEI 里塞展示用假配方**
 * （`SolidLiquidCategory#registerRecipes` 里那两条 `liquid_enchantment_cleanse/assimilation`），
 * 这里照做：只为让人在 JEI 里查得到，**不参与任何实际合成**。
 *
 * ## 为什么要继承 [FluidMixingRecipe]
 *
 * 上游的 JEI 分类（`AbstractLiquidReactionCategory` / `SolidLiquidCategory`）都是
 * `IRecipeCategory<RecipeHolder<FluidMixingRecipe>>`，它们的 `drawBigCauldron(...)`
 * 与槽位助手都只认 `FluidMixingRecipe`。上游自己也是这么干的：
 * `ComplexFluidJeiRecipe extends FluidMixingRecipe` 再额外挂"输入物品"。
 * 它的问题是 `final` + 私有构造器——抄不了类，只能抄这个套路，
 * 于是本类照同一形状自己写一份。
 *
 * @property displayItemInputs 输入物品（上游那个类多出来的就是这一格）
 * @property displayNotes 悬停在配方上时显示的说明（JEI 分类里画不下文字，用 tooltip）
 */
class AddonCauldronRecipe(
    val displayItemInputs: List<ItemIngredientPredicate>,
    /**
     * 展示用的输入流体，**按格子分组**：一组 = 一个流体槽，
     * 组里多个流体表示"任一即可"（例如 `#anvilcraft_fluid:molten_gem` 这种标签，
     * 会把标签里的 6 种熔融宝石都塞进同一格让 JEI 轮播）。
     */
    val displayFluidInputs: List<List<FluidStack>>,
    itemResults: List<ItemStack>,
    fluidResults: List<FluidStack>,
    val displayNotes: List<Component>,
) : FluidMixingRecipe(
    // 基类只认 SizedFluidIngredient，一组取第一个当代表
    displayFluidInputs.map { SizedFluidIngredient.of(it.first().fluid, it.first().amount) },
    itemResults,
    fluidResults,
    false,
)

/** 伪配方与信息页的工厂 */
object AddonJeiEntries {

    /** 分类图标：浮霜桶；取不到就退回大型炼药锅（反应发生的机器） */
    fun iconStack(): ItemStack =
        AddonFluids.byName(FROST)?.bucket?.let(::ItemStack)
            ?: ItemStack(ModBlocks.LARGE_CAULDRON)

    /** 余烬液体的信息页展示物品（余烬桶） */
    fun emberInfoStack(): ItemStack? = AddonFluids.byName(EMBER)?.bucket?.let(::ItemStack)

    /** 余烬液体的信息页文本 */
    fun emberInfo(): Component = Component.translatable("gui.anvilcraft_fluid.info.$EMBER")

    /**
     * 两条展示用伪配方。
     *
     * 示例物品用附魔书：既能表达普通附魔也能表达诅咒，输出还能明确写成"普通书"，
     * 正好对上"洗空的附魔书变回普通书"这条口径。输入物品只是**其中一个代表**，
     * 实际反应对**任意**带附魔的物品都生效。
     *
     * 数值一律读配置（`AnvilCraftFluid.CONFIG`），不写死。
     */
    fun pseudoRecipes(): List<RecipeHolder<FluidMixingRecipe>> = buildList {
        val frost = AddonFluids.byName(FROST)?.source
        val gold = AddonFluids.byName(MOLTEN_GOLD)?.source
        val cursedGold = AddonFluids.byName(CURSED_GOLD)?.source
        // 上游的"液态魔咒"：洗下来的附魔按它的等级公式变成这个流体
        val liquidEnchantment = ModFluids.LIQUID_ENCHANTMENT.get()

        if (frost != null) {
            // 锋利 V → 2^(5-1) = 16 mB
            val level = 5
            add(
                holder(
                    "frost_wash",
                    AddonCauldronRecipe(
                        displayItemInputs = listOf(item(exampleEnchantedBook(Enchantments.SHARPNESS, level))),
                        displayFluidInputs = listOf(
                            listOf(FluidStack(frost, AnvilCraftFluid.CONFIG.frostFluidPerEnchantment)),
                        ),
                        itemResults = listOf(ItemStack(Items.BOOK)),
                        fluidResults = listOf(
                            FluidStack(liquidEnchantment, CauldronItemReactions.liquidAmountFor(level)),
                        ),
                        displayNotes = listOf(
                            Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.frost.0"),
                            Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.frost.1"),
                        ),
                    ),
                ),
            )
        }

        if (gold != null && cursedGold != null) {
            val perCurse = AnvilCraftFluid.CONFIG.goldFluidPerCurse
            add(
                holder(
                    "curse_wash",
                    AddonCauldronRecipe(
                        displayItemInputs = listOf(item(exampleEnchantedBook(Enchantments.BINDING_CURSE, 1))),
                        displayFluidInputs = listOf(listOf(FluidStack(gold, perCurse))),
                        itemResults = listOf(ItemStack(Items.BOOK)),
                        fluidResults = listOf(FluidStack(cursedGold, perCurse)),
                        displayNotes = listOf(
                            Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.curse.0"),
                            Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.curse.1"),
                        ),
                    ),
                ),
            )
        }
    }

    private fun holder(path: String, recipe: FluidMixingRecipe): RecipeHolder<FluidMixingRecipe> =
        RecipeHolder(AnvilCraftFluid.of("jei/$path"), recipe)

    /**
     * 把本模组**自定义类型**的配方（[MultiFluidMixingRecipe]）转成展示用配方，
     * 这样它和伪配方共用同一个 JEI 分类、外观完全一致——
     * 差别只是"多出来的格子"：主流体槽 + 每个额外流体槽 + 物品输入槽。
     */
    fun displayOf(holder: RecipeHolder<MultiFluidMixingRecipe>): RecipeHolder<FluidMixingRecipe> {
        val recipe = holder.value()
        // 主流体：HasCauldronSimple 的谓词能给出 HolderSet（具体流体或整个标签）
        val primary = recipe.cauldron.fluid().fluids()
            .map { holders -> holders.map { holder -> FluidStack(holder.value(), recipe.cauldron.consume()) } }
            .orElse(emptyList())
        val extra = recipe.extraFluids.map { requirement ->
            requirement.candidates().map { fluid -> FluidStack(fluid, requirement.amount) }
        }
        return RecipeHolder(
            holder.id(),
            AddonCauldronRecipe(
                displayItemInputs = recipe.itemIngredients,
                displayFluidInputs = listOf(primary) + extra,
                itemResults = recipe.results.map { result ->
                    result.stack().copy().apply { count = result.maxCount }
                },
                fluidResults = recipe.fluidResults,
                displayNotes = listOf(
                    Component.translatable("gui.anvilcraft_fluid.cauldron_reaction.anvil_strike"),
                ),
            ),
        )
    }

    /** 把示例物品包成"必须有它"的输入谓词 */
    private fun item(stack: ItemStack): ItemIngredientPredicate =
        ItemIngredientPredicate.of(stack.item).build()

    /**
     * 造一本"带指定附魔的附魔书"当示例物品。
     *
     * 附魔是注册表内容，要客户端连上世界之后才取得到（JEI 注册配方时通常已经连上）；
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

    /** JEI 分类标题里用到的类型 id（也用作配方 uid 前缀） */
    val CATEGORY_ID: ResourceLocation = AnvilCraftFluid.of("cauldron_reaction")

    private const val FROST = "frost_fluid"
    private const val EMBER = "ember_fluid"
    private const val MOLTEN_GOLD = "molten_gold"
    private const val CURSED_GOLD = "cursed_gold_fluid"
}
