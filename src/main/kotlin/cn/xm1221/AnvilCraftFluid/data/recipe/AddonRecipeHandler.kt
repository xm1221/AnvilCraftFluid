package cn.xm1221.AnvilCraftFluid.data.recipe

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.anvilcraft.lib.v2.registrum.providers.RegistrumRecipeProvider
import dev.dubhe.anvilcraft.recipe.FluidMixingRecipe

/**
 * 配方 datagen。
 *
 * ## 两种配方机制（都跑在 AnvilCraft 的机器上）
 *
 * - **`anvilcraft:fluid_mixing`（多流体混合）**：大型炼药锅在铁砧撞击时执行，
 *   `LargeCauldronBlockEntity#tryProcessFluidMixingRecipe` 用 `getAllRecipesFor` 全量遍历，
 *   **不筛命名空间**，所以本模组的配方能直接生效；JEI 侧也由 AnvilCraft 的
 *   `FluidReactionCategory` / `SolidLiquidCategory` 自动收录，无需引入 JEI 依赖。
 * - **`anvilcraft:solid_liquid`（固液反应）**：物品输入 + 锅流体条件 → 物品/流体，
 *   用于"熔融钨 + 下界合金碎片 → 远古残骸"这类配方（后续补）。
 *
 * ## ⚠️ 必须用 `save(provider, ResourceLocation)`
 *
 * `AbstractRecipeBuilder#save(RecipeOutput, String)` 内部是
 * `AnvilCraft.of(id).withPrefix(getType() + "/")`——**命名空间被硬编码成 `anvilcraft`**。
 * 附属模组必须传自己的 [net.minecraft.resources.ResourceLocation]，
 * 否则配方会写进上游命名空间并与之冲突。
 */
object AddonRecipeHandler {

    fun init(provider: RegistrumRecipeProvider) {
        val iron = AddonFluids.byName("molten_iron") ?: return
        val ruby = AddonFluids.byName("molten_ruby") ?: return
        val royalSteel = AddonFluids.byName("molten_royal_steel") ?: return

        // 熔融铁 + 熔融红宝石 → 熔融皇家钢（大型炼药锅）
        FluidMixingRecipe.builder()
            .requires(iron.source, 1000)
            .requires(ruby.source, 1000)
            .result(royalSteel.source, 1000)
            .save(provider, AnvilCraftFluid.of("fluid_mixing/molten_royal_steel"))
    }
}
