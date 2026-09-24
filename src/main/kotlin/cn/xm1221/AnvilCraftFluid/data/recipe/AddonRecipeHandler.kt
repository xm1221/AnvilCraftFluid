package cn.xm1221.AnvilCraftFluid.data.recipe

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.anvilcraft.lib.v2.registrum.providers.RegistrumRecipeProvider
import dev.dubhe.anvilcraft.recipe.FluidMixingRecipe
import dev.dubhe.anvilcraft.recipe.anvil.wrap.SolidLiquidRecipe
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * 配方 datagen。
 *
 * ## 两种配方机制（都跑在 AnvilCraft 的机器上）
 *
 * - **`anvilcraft:fluid_mixing`（多流体混合）**：**大型炼药锅**在铁砧撞击时执行，
 *   `LargeCauldronBlockEntity#tryProcessFluidMixingRecipe` 用 `getAllRecipesFor` 全量遍历，
 *   **不筛命名空间**，所以本模组的配方能直接生效；JEI 侧也由 AnvilCraft 的
 *   `FluidReactionCategory` / `SolidLiquidCategory` 自动收录，无需引入 JEI 依赖。
 * - **`anvilcraft:solid_liquid`（固液反应）**：炼药锅里有指定流体 + 上方有物品，
 *   铁砧落下时把物品转成产物（`consume` 指定消耗多少 mB）。本模组的
 *   熔融金属/宝石就走这条路。
 *
 * ## ⚠️ 必须用 `save(provider, ResourceLocation)`
 *
 * `AbstractRecipeBuilder#save(RecipeOutput, String)` 内部是
 * `AnvilCraft.of(id).withPrefix(getType() + "/")`——**命名空间被硬编码成 `anvilcraft`**。
 * 附属模组必须传自己的 [net.minecraft.resources.ResourceLocation]，
 * 否则配方会写进上游命名空间并与之冲突。
 *
 * ## ⚠️ 流体一律用 `RegisteredFluid.source`
 *
 * Registrum 的 `FluidEntry.get()` 返回的是 `flowing_<name>`，源流体在 `getSource()` 上；
 * 用错会写出永远匹配不上的 id（锅/桶里装的都是源流体）。
 */
object AddonRecipeHandler {

    fun init(provider: RegistrumRecipeProvider) {
        fluidMixingRecipes(provider)
        solidLiquidRecipes(provider)
    }

    // ───────────────────── 多流体混合（大型炼药锅） ─────────────────────

    private fun fluidMixingRecipes(provider: RegistrumRecipeProvider) {
        val iron = AddonFluids.byName("molten_iron") ?: return
        val ruby = AddonFluids.byName("molten_ruby") ?: return
        val royalSteel = AddonFluids.byName("molten_royal_steel") ?: return

        // 熔融铁 + 熔融红宝石 → 熔融皇家钢
        FluidMixingRecipe.builder()
            .requires(iron.source, 1000)
            .requires(ruby.source, 1000)
            .result(royalSteel.source, 1000)
            .save(provider, AnvilCraftFluid.of("fluid_mixing/molten_royal_steel"))
    }

    // ───────────────────── 固液反应（炼药锅 + 铁砧） ─────────────────────

    private fun solidLiquidRecipes(provider: RegistrumRecipeProvider) {
        // 熔融钨 + 下界合金碎片 → 远古残骸
        AddonFluids.byName("molten_tungsten")?.let { tungsten ->
            SolidLiquidRecipe.builder()
                .cauldron(tungsten.source)
                .consume(1000)
                .requires(Items.NETHERITE_SCRAP)
                .result(Items.ANCIENT_DEBRIS)
                .save(provider, AnvilCraftFluid.of("solid_liquid/ancient_debris_from_molten_tungsten"))
        }

        // 熔融石英 + 下界石英 → 石英块
        AddonFluids.byName("molten_quartz")?.let { quartz ->
            SolidLiquidRecipe.builder()
                .cauldron(quartz.source)
                .consume(1000)
                .requires(Items.QUARTZ)
                .result(Blocks.QUARTZ_BLOCK)
                .save(provider, AnvilCraftFluid.of("solid_liquid/quartz_block_from_molten_quartz"))
        }

        // 熔融绿宝石 + 绿宝石 → 绿宝石块
        AddonFluids.byName("molten_emerald")?.let { emerald ->
            SolidLiquidRecipe.builder()
                .cauldron(emerald.source)
                .consume(1000)
                .requires(Items.EMERALD)
                .result(Blocks.EMERALD_BLOCK)
                .save(provider, AnvilCraftFluid.of("solid_liquid/emerald_block_from_molten_emerald"))
        }

        // 熔融铁 + 铁锭 → 铁块（基础示例，也用来验证矿物熔融体系）
        AddonFluids.byName("molten_iron")?.let { iron ->
            SolidLiquidRecipe.builder()
                .cauldron(iron.source)
                .consume(1000)
                .requires(Items.IRON_INGOT, 9)
                .result(Blocks.IRON_BLOCK)
                .save(provider, AnvilCraftFluid.of("solid_liquid/iron_block_from_molten_iron"))
        }
    }
}
