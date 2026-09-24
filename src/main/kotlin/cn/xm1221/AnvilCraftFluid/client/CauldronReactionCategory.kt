package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import dev.dubhe.anvilcraft.integration.jei.category.AbstractLiquidReactionCategory
import dev.dubhe.anvilcraft.integration.jei.util.JeiFluidUtil
import dev.dubhe.anvilcraft.recipe.FluidMixingRecipe
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.item.crafting.RecipeHolder
import net.neoforged.neoforge.fluids.FluidType

/**
 * 「炼药锅流体反应」JEI 分类。
 *
 * ## 直接继承上游的分类（用户要求："为什么不直接抄"）
 *
 * 继承 [AbstractLiquidReactionCategory]——AnvilCraft 的 `SolidLiquidCategory` /
 * `FluidReactionCategory` 都用它作基类，所以背景（那只大型炼药锅的模型绘制
 * `drawBigCauldron`）、槽位素材、箭头、输入/输出的排布规则**全部与上游一致**，
 * 不用自己画任何东西。
 *
 * ## 与上游的唯一差别：多一个**物品输入槽**
 *
 * 上游的 `ComplexFluidJeiRecipe` 在流体输入之外还挂"输入物品"，
 * 而那个类是 `final` + 私有构造器，抄不了类——所以本模组的展示配方
 * [AddonCauldronRecipe] 照同一套路自己继承 [FluidMixingRecipe]，
 * 并把"输入物品"额外暴露出来；本分类在此基础上按上游的
 * `inputPosition/itemOutputPosition/fluidOutputPosition` 规则排槽，
 * 就是把那一格补上而已。
 */
class CauldronReactionCategory(guiHelper: IGuiHelper) : AbstractLiquidReactionCategory(
    guiHelper,
    guiHelper.createDrawableItemStack(AddonJeiEntries.iconStack()),
) {

    companion object {
        /**
         * 本分类的配方类型。
         *
         * 用 `createRecipeHolderType` 造，与上游 `AnvilCraftJeiPlugin.SOLID_LIQUID`
         * 同一种形状（`RecipeType<RecipeHolder<FluidMixingRecipe>>`），
         * 这样上游那套渲染/助手方法能直接用。
         */
        val TYPE: RecipeType<RecipeHolder<FluidMixingRecipe>> =
            RecipeType.createRecipeHolderType(AddonJeiEntries.CATEGORY_ID)

        private const val SLOT_INNER = 16
        private const val SLOT_OFFSET = 1

        /** 流体槽按"一桶"画满格 */
        private const val FLUID_CAPACITY = FluidType.BUCKET_VOLUME.toLong()
    }

    override fun getRecipeType(): RecipeType<RecipeHolder<FluidMixingRecipe>> = TYPE

    override fun getTitle(): Component =
        Component.translatable("gui.anvilcraft_fluid.category.cauldron_reaction")

    override fun setRecipe(
        builder: IRecipeLayoutBuilder,
        recipeHolder: RecipeHolder<FluidMixingRecipe>,
        focuses: IFocusGroup,
    ) {
        val recipe = recipeHolder.value as? AddonCauldronRecipe ?: return
        val itemInputs = recipe.displayItemInputs
        val fluidInputs = recipe.displayFluidInputs
        val inputCount = itemInputs.size + fluidInputs.size

        // 输入：物品槽（上游那类多出来的就是这一格）+ 流体槽，按上游的输入网格规则排
        itemInputs.forEachIndexed { index, ingredient ->
            val position = inputPosition(inputCount, index)
            builder.addSlot(
                RecipeIngredientRole.INPUT,
                position.x + SLOT_OFFSET,
                position.y + SLOT_OFFSET,
            ).addItemStacks(ingredient.items.toList())
        }
        fluidInputs.forEachIndexed { index, candidates ->
            val position = inputPosition(inputCount, itemInputs.size + index)
            // 一组 = 一个槽；组里多流体表示"任一即可"（标签），JEI 会自己轮播
            JeiFluidUtil.addFluidSlot(
                builder,
                RecipeIngredientRole.INPUT,
                position.x + SLOT_OFFSET,
                position.y + SLOT_OFFSET,
                SLOT_INNER,
                SLOT_INNER,
                FLUID_CAPACITY,
                true,
                candidates,
            )
        }

        // 输出：物品 + 流体（两类都有时上游会拆成两列）
        val itemResults = recipe.itemResults
        val fluidResults = recipe.fluidResults
        val splitOutputColumns = itemResults.isNotEmpty() && fluidResults.isNotEmpty()
        itemResults.forEachIndexed { index, stack ->
            val position = itemOutputPosition(itemResults.size, index, splitOutputColumns)
            builder.addSlot(
                RecipeIngredientRole.OUTPUT,
                position.x + SLOT_OFFSET,
                position.y + SLOT_OFFSET,
            ).addItemStack(stack.copy())
        }
        fluidResults.forEachIndexed { index, fluid ->
            val position = fluidOutputPosition(fluidResults.size, index, splitOutputColumns)
            JeiFluidUtil.addFluidSlot(
                builder,
                RecipeIngredientRole.OUTPUT,
                position.x + SLOT_OFFSET,
                position.y + SLOT_OFFSET,
                SLOT_INNER,
                SLOT_INNER,
                FLUID_CAPACITY,
                true,
                listOf(fluid),
            )
        }
    }

    /** 背景直接交给上游画（大型炼药锅模型 + 箭头） */
    override fun draw(
        recipeHolder: RecipeHolder<FluidMixingRecipe>,
        recipeSlotsView: IRecipeSlotsView,
        guiGraphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double,
    ) {
        drawBigCauldron(recipeHolder, recipeSlotsView, guiGraphics, mouseX, mouseY)
    }

    /**
     * 说明文字走 tooltip。
     *
     * 上游这套布局里没有文字区（162×64 全给了锅的模型），画上去会盖住机器，
     * 所以把"洗掉全部附魔 / 每条按 2^(等级-1) mB 产出液态魔咒"这类说明做成悬停提示。
     */
    override fun getTooltipStrings(
        recipeHolder: RecipeHolder<FluidMixingRecipe>,
        recipeSlotsView: IRecipeSlotsView,
        mouseX: Double,
        mouseY: Double,
    ): List<Component> = (recipeHolder.value as? AddonCauldronRecipe)?.displayNotes ?: emptyList()
}
