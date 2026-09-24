package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.FluidType

/**
 * 一条**展示用伪配方**（pseudo-recipe）。
 *
 * 本模组的两个炼药锅行为是**代码反应**（见
 * [cn.xm1221.AnvilCraftFluid.event.CauldronItemReactions]），不是数据配方——
 * 因为它们要"任意附魔物品 → 它自己去附魔 + 按附魔条数产出"，
 * 而 `RecipeResult` 的产物必须是具体 `Item`，配方体系表达不了。
 *
 * 上游 AnvilCraft 对同类代码反应的处理方式就是**手工往 JEI 里塞展示用的假配方**
 * （`SolidLiquidCategory#registerRecipes` 里 `registration.addRecipes(...)` 那几条
 * `liquid_enchantment_cleanse/assimilation`），这里照同样的思路做：
 * 只为了让人在 JEI 里查得到，**不参与任何实际合成**。
 *
 * @property inputItem 示例输入物品（"任意附魔物品"的其中一个代表）
 * @property inputFluid 输入流体与量
 * @property outputItem 输出物品
 * @property outputFluid 输出流体与量
 * @property notes 画在配方下方的一两行说明（本地化键）
 */
data class CauldronReactionJeiRecipe(
    val inputItem: ItemStack,
    val inputFluid: FluidStack,
    val outputItem: ItemStack,
    val outputFluid: FluidStack,
    val notes: List<Component>,
)

/**
 * 「炼药锅流体反应」JEI 分类：`输入物品 + 输入流体 → 输出物品 + 输出流体`。
 *
 * 布局（150×54）：
 * ```
 * [物品] [流体]  →  [物品] [流体]
 *  洗掉全部附魔
 *  每条按 2^(等级-1) mB 产出液态魔咒
 * ```
 * 背景用 [IGuiHelper.createBlankDrawable]（不需要自己的贴图资源），
 * 槽位背景与箭头都用 JEI 自带素材。
 */
class CauldronReactionCategory(private val guiHelper: IGuiHelper) :
    IRecipeCategory<CauldronReactionJeiRecipe> {

    companion object {
        /** 本分类的配方类型；uid = `anvilcraft_fluid:cauldron_reaction` */
        val TYPE: RecipeType<CauldronReactionJeiRecipe> = RecipeType.create(
            AnvilCraftFluid.MOD_ID,
            "cauldron_reaction",
            CauldronReactionJeiRecipe::class.java,
        )

        private const val WIDTH = 150
        private const val HEIGHT = 54

        /** 槽位纵向位置：JEI 槽位是 18×18 */
        private const val SLOT_Y = 8
        private const val SLOT_SIZE = 16

        /** 一桶，用来决定流体槽的"满格"高度 */
        private const val FLUID_CAPACITY = FluidType.BUCKET_VOLUME.toLong()
    }

    private val background: IDrawable = guiHelper.createBlankDrawable(WIDTH, HEIGHT)
    private val arrow: IDrawable = guiHelper.recipeArrow

    /** 分类图标：优先用浮霜桶（没有就退回大型炼药锅） */
    private val icon: IDrawable = guiHelper.createDrawableItemStack(
        AddonJeiEntries.iconStack(),
    )

    override fun getRecipeType(): RecipeType<CauldronReactionJeiRecipe> = TYPE

    override fun getTitle(): Component =
        Component.translatable("gui.anvilcraft_fluid.category.cauldron_reaction")

    override fun getIcon(): IDrawable = icon

    override fun getBackground(): IDrawable = background

    override fun getWidth(): Int = WIDTH

    override fun getHeight(): Int = HEIGHT

    override fun setRecipe(
        builder: IRecipeLayoutBuilder,
        recipe: CauldronReactionJeiRecipe,
        focuses: IFocusGroup,
    ) {
        // 输入：物品 + 流体
        builder.addInputSlot(2, SLOT_Y)
            .setStandardSlotBackground()
            .addItemStack(recipe.inputItem)
        builder.addInputSlot(24, SLOT_Y)
            .setFluidRenderer(FLUID_CAPACITY, true, SLOT_SIZE, SLOT_SIZE)
            .addFluidStack(recipe.inputFluid.fluid, recipe.inputFluid.amount.toLong())

        // 输出：物品 + 流体
        builder.addOutputSlot(72, SLOT_Y)
            .setOutputSlotBackground()
            .addItemStack(recipe.outputItem)
        builder.addOutputSlot(94, SLOT_Y)
            .setFluidRenderer(FLUID_CAPACITY, true, SLOT_SIZE, SLOT_SIZE)
            .addFluidStack(recipe.outputFluid.fluid, recipe.outputFluid.amount.toLong())
    }

    override fun draw(
        recipe: CauldronReactionJeiRecipe,
        recipeSlotsView: IRecipeSlotsView,
        guiGraphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double,
    ) {
        arrow.draw(guiGraphics, 46, SLOT_Y + 1)
        val font = Minecraft.getInstance().font
        recipe.notes.forEachIndexed { index, note ->
            guiGraphics.drawString(font, note, 2, 30 + index * 10, NOTE_COLOR, false)
        }
    }
}

/** 说明文字的颜色（深灰，两种主题下都看得清） */
private const val NOTE_COLOR: Int = 0xFF404040.toInt()
