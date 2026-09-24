package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import dev.dubhe.anvilcraft.block.LargeCauldronBlock
import dev.dubhe.anvilcraft.block.state.Cube3x3PartHalf
import dev.dubhe.anvilcraft.client.support.RenderSupport
import dev.dubhe.anvilcraft.init.block.ModBlocks
import dev.dubhe.anvilcraft.integration.jei.category.AbstractLiquidReactionCategory
import dev.dubhe.anvilcraft.integration.jei.util.JeiBlockIngredientUtil
import dev.dubhe.anvilcraft.integration.jei.util.JeiFluidUtil
import dev.dubhe.anvilcraft.integration.jei.util.JeiItemUtil
import dev.dubhe.anvilcraft.integration.jei.util.JeiRenderHelper
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
 * ## 外观直接沿用上游
 *
 * 继承 [AbstractLiquidReactionCategory]——AnvilCraft 的 `SolidLiquidCategory` /
 * `FluidReactionCategory` 都用它作基类，背景（大型炼药锅 + 巨型铁砧的模型绘制）、
 * 槽位素材、箭头、输入/输出的排布规则全部与上游一致。
 *
 * ## ⚠️ 为什么 `draw` 要自己重画，而不能直接调 `drawBigCauldron`
 *
 * 上游那个方法里，画输入格时是这么取格数的：
 *
 * ```java
 * if (recipe instanceof ComplexFluidJeiRecipe complexRecipe) {
 *     int inputCount = complexRecipe.getDisplayFluidInputCount() + complexRecipe.getInputItems().size();
 * } else {
 *     int inputCount = recipe.getFluidIngredients().size();   // ← 只数流体！
 * }
 * ```
 *
 * 本模组的展示配方 [AddonCauldronRecipe] **不是** `ComplexFluidJeiRecipe`
 * （那个类 `final` 且构造器私有，抄不了），于是会落到 `else` 分支——
 * 只画流体那几格，而我们摆了"物品 + 流体"全部槽，槽位与底色框就错位了。
 * 所以这里照它的实现**自己画一遍**，只把输入格数改成"流体组数 + 物品数"。
 */
class CauldronReactionCategory(guiHelper: IGuiHelper) : AbstractLiquidReactionCategory(
    guiHelper,
    guiHelper.createDrawableItemStack(AddonJeiEntries.iconStack()),
) {

    companion object {
        /** 本分类的配方类型（与上游 `SOLID_LIQUID` 同形状，便于复用其助手方法） */
        val TYPE: RecipeType<RecipeHolder<FluidMixingRecipe>> =
            RecipeType.createRecipeHolderType(AddonJeiEntries.CATEGORY_ID)

        private const val SLOT_INNER = 16
        private const val SLOT_OFFSET = 1

        /** 流体槽按"一桶"画满格 */
        private const val FLUID_CAPACITY = FluidType.BUCKET_VOLUME.toLong()

        /** 与上游 `AbstractLiquidReactionCategory` 构造器里同样的两个模型 */
        private const val MODEL_SCALE = 7.5f
    }

    /** 巨型铁砧（与上游同一个渲染前状态） */
    private val giantAnvilState =
        JeiBlockIngredientUtil.getRenderablePreviewState(ModBlocks.GIANT_ANVIL.getDefaultState())

    /** 大型炼药锅（只画正中那一块） */
    private val largeCauldronState = ModBlocks.LARGE_CAULDRON.getDefaultState()
        .setValue(LargeCauldronBlock.HALF, Cube3x3PartHalf.MID_CENTER)

    override fun getRecipeType(): RecipeType<RecipeHolder<FluidMixingRecipe>> = TYPE

    override fun getTitle(): Component =
        Component.translatable("gui.anvilcraft_fluid.category.cauldron_reaction")

    override fun setRecipe(
        builder: IRecipeLayoutBuilder,
        recipeHolder: RecipeHolder<FluidMixingRecipe>,
        focuses: IFocusGroup,
    ) {
        val recipe = recipeHolder.value as? AddonCauldronRecipe ?: return
        val fluidInputs = recipe.displayFluidInputs
        val itemInputs = recipe.displayItemInputs
        val inputCount = fluidInputs.size + itemInputs.size

        // 输入：***先流体后物品***，与上游 setComplexRecipe 的顺序一致
        fluidInputs.forEachIndexed { index, candidates ->
            val position = inputPosition(inputCount, index)
            // 一组 = 一个槽；组里多流体表示"任一即可"（标签），JEI 会自己轮播
            JeiFluidUtil.addFluidSlot(
                builder,
                RecipeIngredientRole.INPUT,
                position.x + SLOT_OFFSET,
                position.y + SLOT_OFFSET,
                SLOT_INNER,
                SLOT_INNER,
                FLUID_CAPACITY,
                false,
                candidates,
            )
        }
        itemInputs.forEachIndexed { index, ingredient ->
            val position = inputPosition(inputCount, fluidInputs.size + index)
            // 用上游的助手画（带数量角标）
            JeiItemUtil.addSlotWithCount(
                builder,
                position.x + SLOT_OFFSET,
                position.y + SLOT_OFFSET,
                ingredient,
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

    /**
     * 背景：照上游 `drawBigCauldron` 重画一遍（唯一差别是输入格数含物品），
     * 槽位背景、箭头、铁砧与炼药锅模型都沿用上游的素材与参数。
     */
    override fun draw(
        recipeHolder: RecipeHolder<FluidMixingRecipe>,
        recipeSlotsView: IRecipeSlotsView,
        guiGraphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double,
    ) {
        val recipe = recipeHolder.value as? AddonCauldronRecipe ?: return
        val itemResults = recipe.itemResults
        val fluidResults = recipe.fluidResults

        // ⚠️ 输入格数 = 流体组数 + 物品数（上游这里只数流体，会与槽位错位）
        val inputCount = recipe.displayFluidInputs.size + recipe.displayItemInputs.size
        for (index in 0 until inputCount) {
            val position = inputPosition(inputCount, index)
            slot.draw(guiGraphics, position.x, position.y)
        }

        val splitOutputColumns = itemResults.isNotEmpty() && fluidResults.isNotEmpty()
        itemResults.indices.forEach { index ->
            val position = itemOutputPosition(itemResults.size, index, splitOutputColumns)
            slot.draw(guiGraphics, position.x, position.y)
        }
        fluidResults.indices.forEach { index ->
            val position = fluidOutputPosition(fluidResults.size, index, splitOutputColumns)
            slot.draw(guiGraphics, position.x, position.y)
        }

        arrowIn.draw(guiGraphics, 47, 30)
        arrowOut.draw(guiGraphics, 99, 29)

        val anvilYOffset = JeiRenderHelper.getAnvilAnimationOffset(timer) / 3.0f
        RenderSupport.renderBlock(
            guiGraphics,
            giantAnvilState,
            81f,
            23f + anvilYOffset,
            20f,
            MODEL_SCALE,
            RenderSupport.SINGLE_BLOCK,
        )
        RenderSupport.renderBlock(
            guiGraphics,
            largeCauldronState,
            81f,
            45f,
            10f,
            MODEL_SCALE,
            RenderSupport.SINGLE_BLOCK,
        )
    }

    /**
     * 说明文字走 tooltip：上游这套布局 162×64 全给了机器模型，没有文字区，
     * 画上去会盖住铁砧和锅。
     */
    override fun getTooltipStrings(
        recipeHolder: RecipeHolder<FluidMixingRecipe>,
        recipeSlotsView: IRecipeSlotsView,
        mouseX: Double,
        mouseY: Double,
    ): List<Component> = (recipeHolder.value as? AddonCauldronRecipe)?.displayNotes ?: emptyList()
}
