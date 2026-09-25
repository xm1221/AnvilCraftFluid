package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.recipe.AddonRecipeTypes
import dev.dubhe.anvilcraft.init.block.ModBlocks
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * JEI 插件：把两个**代码反应**以"伪配方"形式展示出来（用户要求）。
 *
 * ## 为什么是伪配方
 *
 * 浮霜洗附魔 / 熔融金洗诅咒的行为要实现"**任意**附魔物品 → 它自己去附魔 + 按附魔条数产出"，
 * 而 AnvilCraft 的配方体系里产物必须是**具体物品**（`RecipeResult.result` 是 `Item`，
 * 结果修饰器也没有"沿用输入物品"），所以行为只能写成代码反应
 * （见 [cn.xm1221.AnvilCraftFluid.event.CauldronItemReactions]）。
 *
 * 上游 AnvilCraft 对同类代码反应的处理就是手工往 JEI 里塞展示用假配方
 * （`SolidLiquidCategory#registerRecipes` 里的 `liquid_enchantment_cleanse/assimilation`），
 * 本插件照同样思路做，只是用**自己的分类**——因为上游那个 `ComplexFluidJeiRecipe`
 * 是 `final` 且构造器私有，塞进它的分类里显示不出"输入物品"那一格。
 *
 * ## 注意
 *
 * 本类**只在客户端**加载，且只有装了 JEI 才会被 JEI 扫描到（`@JeiPlugin`），
 * 未装 JEI 时不会被任何代码触碰，所以 JEI 只是编译期依赖。
 */
@JeiPlugin
class AddonJeiPlugin : IModPlugin {

    override fun getPluginUid(): ResourceLocation = AnvilCraftFluid.of("jei")

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(
            CauldronReactionCategory(registration.jeiHelpers.guiHelper),
        )
    }
    override fun registerRecipes(registration: IRecipeRegistration) {
        // 1) 代码反应的伪配方（浮霜洗附魔 / 熔融金洗诅咒）
        // 2) 自定义类型 anvilcraft_fluid:multi_fluid_mixing 的真实配方（从配方管理器读出来转成展示用）
        val fromManager = Minecraft.getInstance().level?.recipeManager
            ?.getAllRecipesFor(AddonRecipeTypes.MULTI_FLUID_MIXING_TYPE.get())
            ?.map(AddonJeiEntries::displayOf)
            .orEmpty()

        registration.addRecipes(
            CauldronReactionCategory.TYPE,
            AddonJeiEntries.pseudoRecipes() + fromManager,
        )

        // 余烬流体不是配方行为（物品站在流体里就被修），用信息页说明
        AddonJeiEntries.emberInfoStack()?.let { stack ->
            registration.addItemStackInfo(stack, AddonJeiEntries.emberInfo())
        }
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        // 这两个反应发生在**大型炼药锅**里，所以催化剂就是它
        registration.addRecipeCatalyst(
            ItemStack(ModBlocks.LARGE_CAULDRON),
            CauldronReactionCategory.TYPE,
        )
    }
}
