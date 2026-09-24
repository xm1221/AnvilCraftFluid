package cn.xm1221.AnvilCraftFluid.data.recipe

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.anvilcraft.lib.v2.registrum.providers.RegistrumRecipeProvider
import cn.xm1221.AnvilCraftFluid.init.AddonFluidTags
import cn.xm1221.AnvilCraftFluid.recipe.FluidRequirement
import cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe
import dev.anvilcraft.lib.v2.util.predicate.ChanceItemStack
import dev.anvilcraft.lib.v2.util.predicate.ItemIngredientPredicate
import dev.dubhe.anvilcraft.recipe.component.HasCauldronSimple
import net.neoforged.neoforge.fluids.FluidStack
import dev.dubhe.anvilcraft.init.block.ModBlocks
import dev.dubhe.anvilcraft.recipe.FluidMixingRecipe
import dev.dubhe.anvilcraft.recipe.anvil.wrap.SolidLiquidRecipe
import dev.dubhe.anvilcraft.recipe.anvil.wrap.SuperHeatingRecipe
import dev.dubhe.anvilcraft.recipe.anvil.wrap.TimeWarpRecipe
import net.minecraft.world.item.Items
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * 配方 datagen。
 *
 * ## 四套机制（都跑在 AnvilCraft 的机器上，各自能表达的东西不一样）
 *
 * | 类型 | 输入 | 输出 | 本模组用来做什么 |
 * | --- | --- | --- | --- |
 * | `anvilcraft:super_heating` 高温熔炼 | **1 种锅里流体** + 物品 | 物品 / 流体（`.transform(锅, mB)`） | **熔融**：方块或 9 物品 → 一桶熔融流体 |
 * | `anvilcraft:time_warp` 时移 | **1 种锅里流体** + 物品 | 物品 / 流体 | 下界合金 → 远古残骸 |
 * | `anvilcraft:fluid_mixing` 多流体混合 | **多种**流体（大型炼药锅，铁砧砸） | 物品 / 流体 | 熔融黄玉 + 熔融铁 → 磁铁块 |
 * | `anvilcraft:solid_liquid` 固液反应 | **1 种锅里流体** + 物品 | 物品 / 流体 | 熔融流体 + 材料 → 方块 |
 *
 * ## ⚠️ 上限：锅+物品类的配方**只能有一种流体**
 *
 * `HasCauldronSimple#fluid` 是**单个** `FluidStackPredicate`——所以"时移/固液/高温"
 * 都做不出"两种流体"的配方。两种流体只能走 `fluid_mixing`（但它**不能吃物品**），
 * 或者写成代码反应（像浮霜那样，见 `event/CauldronItemReactions`）。
 * 这就是"熔融宝石 + 钻石 + 熔融铁 → 熔融皇家钢"（两流体 + 一物品）无法用数据配方表达的原因。
 *
 * ## ⚠️ 必须用 `save(provider, ResourceLocation)`
 *
 * `AbstractRecipeBuilder#save(RecipeOutput, String)` 内部是
 * `AnvilCraft.of(id).withPrefix(getType() + "/")`——**命名空间被硬编码成 `anvilcraft`**。
 * 附属模组必须传自己的 [net.minecraft.resources.ResourceLocation]。
 *
 * ## ⚠️ 流体一律用 `RegisteredFluid.source`
 *
 * Registrum 的 `FluidEntry.get()` 返回的是 `flowing_<name>`，源流体在 `getSource()` 上；
 * 用错会写出永远匹配不上的 id。另外 `fluid.getSource<T>()` 是**无检查强转**，
 * 千万别再写 `getSource<BaseFlowingFluid.Flowing>()`（会崩）。
 */
object AddonRecipeHandler {

    fun init(provider: RegistrumRecipeProvider) {
        meltingRecipes(provider)
        timeWarpRecipes(provider)
        fluidMixingRecipes(provider)
        multiFluidMixingRecipes(provider)
        coolingRecipes(provider)
        solidLiquidRecipes(provider)
    }

    // ─────────────── 自定义类型：多流体 + 物品 → 物品/流体 ───────────────

    /**
     * 这些配方用**本模组自己的配方类型** `anvilcraft_fluid:multi_fluid_mixing`
     * （见 [cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe]），
     * 因为上游现成类型都表达不了"两种流体"：
     * 锅+物品类只能带一种锅里流体，`fluid_mixing` 又没有物品槽。
     *
     * - **熔融皇家钢**：任意熔融宝石（`#molten_gem` 标签）+ 钻石 + 熔融铁 → 熔融皇家钢
     * - **矿石**：熔融红宝石 + 熔融金属 → 对应**深层**矿石；
     *   熔融蓝宝石 + 熔融金属 → 对应**普通**矿石
     *   （⚠️ AnvilCraft 的金属**只有深层矿石**，没有普通矿石版本，
     *    所以熔融钨只有深层那一侧；原版铁/金/铜两种都有）
     */
    private fun multiFluidMixingRecipes(provider: RegistrumRecipeProvider) {
        val iron = AddonFluids.byName("molten_iron") ?: return
        val ruby = AddonFluids.byName("molten_ruby") ?: return
        val sapphire = AddonFluids.byName("molten_sapphire") ?: return

        // 任意熔融宝石（红/黄/蓝/绿，不含石英与紫水晶）+ 钻石 + 熔融铁 → 熔融皇家钢
        AddonFluids.byName("molten_royal_steel")?.let { royalSteel ->
            multiFluid(
                provider,
                "multi_fluid_mixing/molten_royal_steel",
                items = listOf(ItemIngredientPredicate.Builder.item().of(Items.DIAMOND).build()),
                results = emptyList(),
                cauldron = HasCauldronSimple.fluid(iron.source).consume(BUCKET).build(),
                // ⚠️ 用窄标签（只有红/黄/蓝/绿四种），不是 MOLTEN_GEM——
                //    熔融石英与熔融紫水晶**不能**炼皇家钢（用户拍板）
                extra = listOf(FluidRequirement.of(AddonFluidTags.ROYAL_STEEL_GEMS, BUCKET)),
                fluidResults = listOf(FluidStack(royalSteel.source, BUCKET)),
            )
        }

        // 熔融宝石 + 熔融金属 → 矿石
        // ⚠️ AnvilCraft 的金属**只有深层矿石**，所以只有原版铁/金/铜有"普通矿石"那一档
        val ores = listOf(
            Triple("molten_iron", Blocks.DEEPSLATE_IRON_ORE, Blocks.IRON_ORE),
            Triple("molten_gold", Blocks.DEEPSLATE_GOLD_ORE, Blocks.GOLD_ORE),
            Triple("molten_copper", Blocks.DEEPSLATE_COPPER_ORE, Blocks.COPPER_ORE),
            Triple("molten_tungsten", ModBlocks.DEEPSLATE_TUNGSTEN_ORE.get(), null),
            Triple("molten_lead", ModBlocks.DEEPSLATE_LEAD_ORE.get(), null),
            Triple("molten_silver", ModBlocks.DEEPSLATE_SILVER_ORE.get(), null),
            Triple("molten_tin", ModBlocks.DEEPSLATE_TIN_ORE.get(), null),
            Triple("molten_zinc", ModBlocks.DEEPSLATE_ZINC_ORE.get(), null),
            Triple("molten_titanium", ModBlocks.DEEPSLATE_TITANIUM_ORE.get(), null),
            Triple("molten_uranium", ModBlocks.DEEPSLATE_URANIUM_ORE.get(), null),
        )
        for ((metalName, deepslate, normal) in ores) {
            val metal = AddonFluids.byName(metalName) ?: continue

            multiFluid(
                provider,
                "multi_fluid_mixing/deepslate_ore/$metalName",
                items = emptyList(),
                results = listOf(ChanceItemStack.of(deepslate, 1)),
                cauldron = HasCauldronSimple.fluid(ruby.source).consume(GEM_CATALYST).build(),
                extra = listOf(FluidRequirement.of(metal.source, ORE_FLUID)),
                fluidResults = emptyList(),
            )

            if (normal != null) {
                multiFluid(
                    provider,
                    "multi_fluid_mixing/ore/$metalName",
                    items = emptyList(),
                    results = listOf(ChanceItemStack.of(normal, 1)),
                    cauldron = HasCauldronSimple.fluid(sapphire.source).consume(GEM_CATALYST).build(),
                    extra = listOf(FluidRequirement.of(metal.source, ORE_FLUID)),
                    fluidResults = emptyList(),
                )
            }
        }

        // 绿宝石：熔融绿宝石 + 熔融蓝宝石 → 绿宝石矿；熔融绿宝石 + 熔融红宝石 → 深层绿宝石矿
        AddonFluids.byName("molten_emerald")?.let { emerald ->
            multiFluid(
                provider,
                "multi_fluid_mixing/emerald_ore",
                items = emptyList(),
                results = listOf(ChanceItemStack.of(Items.EMERALD_ORE, 1)),
                cauldron = HasCauldronSimple.fluid(sapphire.source).consume(GEM_CATALYST).build(),
                extra = listOf(FluidRequirement.of(emerald.source, ORE_FLUID)),
                fluidResults = emptyList(),
            )
            multiFluid(
                provider,
                "multi_fluid_mixing/deepslate_emerald_ore",
                items = emptyList(),
                results = listOf(ChanceItemStack.of(Items.DEEPSLATE_EMERALD_ORE, 1)),
                cauldron = HasCauldronSimple.fluid(ruby.source).consume(GEM_CATALYST).build(),
                extra = listOf(FluidRequirement.of(emerald.source, ORE_FLUID)),
                fluidResults = emptyList(),
            )
        }
    }

    // ───────────────────────── 冷却：一锅熔融流体 → 方块 ─────────────────────────

    /**
     * 「铁砧落到装有熔融流体的炼药锅（满）上时，产出对应的块」（用户要求）。
     *
     * 用现成的 `anvilcraft:solid_liquid`：**不写 `requires(...)`** 就是"只要锅里有这种流体"，
     * 铁砧落下即把满锅（1000 mB）换成对应的方块。这正好是熔融配方的逆过程。
     *
     * ⚠️ 无物品输入的配方要靠 `HasCauldron` 谓词才会被大型炼药锅匹配（见
     * `LargeCauldronBlockEntity#triggerOneRecipe` 的两条匹配路径），而 `solid_liquid`
     * 自带该谓词，所以不用像自研类型那样额外补一个。
     */
    private fun coolingRecipes(provider: RegistrumRecipeProvider) {
        val blockByFluid = listOf(
            "molten_iron" to Blocks.IRON_BLOCK,
            "molten_gold" to Blocks.GOLD_BLOCK,
            "molten_copper" to Blocks.COPPER_BLOCK,
            "molten_tungsten" to ModBlocks.TUNGSTEN_BLOCK.get(),
            "molten_royal_steel" to ModBlocks.ROYAL_STEEL_BLOCK.get(),
            "molten_lead" to ModBlocks.LEAD_BLOCK.get(),
            "molten_silver" to ModBlocks.SILVER_BLOCK.get(),
            "molten_tin" to ModBlocks.TIN_BLOCK.get(),
            "molten_zinc" to ModBlocks.ZINC_BLOCK.get(),
            "molten_titanium" to ModBlocks.TITANIUM_BLOCK.get(),
            "molten_uranium" to ModBlocks.URANIUM_BLOCK.get(),
            "molten_ruby" to ModBlocks.RUBY_BLOCK.get(),
            "molten_sapphire" to ModBlocks.SAPPHIRE_BLOCK.get(),
            "molten_topaz" to ModBlocks.TOPAZ_BLOCK.get(),
            "molten_emerald" to Blocks.EMERALD_BLOCK,
            "cursed_gold_fluid" to ModBlocks.CURSED_GOLD_BLOCK.get(),
            "molten_quartz" to Blocks.QUARTZ_BLOCK,
            "molten_amethyst" to Blocks.AMETHYST_BLOCK,
        )
        for ((fluidName, block) in blockByFluid) {
            val fluid = AddonFluids.byName(fluidName) ?: continue
            SolidLiquidRecipe.builder()
                .cauldron(fluid.source)
                .consume(BUCKET)
                .result(block)
                .save(provider, AnvilCraftFluid.of("solid_liquid/cooling/${fluidName.removePrefix("molten_")}"))
        }
    }

    /** 交给本模组自己的配方类型（`provider` 就是 `RecipeOutput`，直接 accept 即可） */
    private fun multiFluid(
        provider: RegistrumRecipeProvider,
        path: String,
        items: List<ItemIngredientPredicate>,
        results: List<ChanceItemStack>,
        cauldron: HasCauldronSimple,
        extra: List<FluidRequirement>,
        fluidResults: List<FluidStack>,
    ) {
        provider.accept(
            AnvilCraftFluid.of(path),
            MultiFluidMixingRecipe(items, results, cauldron, extra, fluidResults, Int.MAX_VALUE),
            null,
        )
    }

    // ───────────────────────── 熔融：方块/物品 → 一桶流体 ─────────────────────────

    /**
     * 「一个金属或宝石块熔融得到一桶对应流体」。
     *
     * 用**高温熔炼**（`super_heating`）+ `.transform(我们的锅方块, 1000)`：
     * 上游的"石头 → 熔岩"就是这么写的（`super_heating/lava_from_cobblestone`，
     * 4 石头 + 石灰粉 + `.transform(Blocks.LAVA_CAULDRON, 1000)`），
     * 所以这里照抄同一形状——铁砧砸下去，锅里的东西换成 1000 mB 我们的熔融流体。
     *
     * ⚠️ `transform(锅方块, mB)` 里的锅方块靠 `WrapUtils.cauldron2Fluid` 反查流体，
     * 因此**锅的命名必须符合 `<流体名>_cauldron`**——这条硬规则再一次生效。
     *
     * 石英与紫水晶按用户口径是**九个物品**（不是方块）：9 石英 → 熔融石英、
     * 9 紫水晶碎片 → 熔融紫水晶。
     */
    private fun meltingRecipes(provider: RegistrumRecipeProvider) {
        // 金属块 / 宝石块 → 1000 mB
        val blockInputs: List<Pair<String, Block>> = listOf(
            "molten_iron" to Blocks.IRON_BLOCK,
            "molten_gold" to Blocks.GOLD_BLOCK,
            "molten_copper" to Blocks.COPPER_BLOCK,
            "molten_tungsten" to ModBlocks.TUNGSTEN_BLOCK.get(),
            "molten_royal_steel" to ModBlocks.ROYAL_STEEL_BLOCK.get(),
            "molten_lead" to ModBlocks.LEAD_BLOCK.get(),
            "molten_silver" to ModBlocks.SILVER_BLOCK.get(),
            "molten_tin" to ModBlocks.TIN_BLOCK.get(),
            "molten_zinc" to ModBlocks.ZINC_BLOCK.get(),
            "molten_titanium" to ModBlocks.TITANIUM_BLOCK.get(),
            "molten_uranium" to ModBlocks.URANIUM_BLOCK.get(),
            "molten_ruby" to ModBlocks.RUBY_BLOCK.get(),
            "molten_sapphire" to ModBlocks.SAPPHIRE_BLOCK.get(),
            "molten_topaz" to ModBlocks.TOPAZ_BLOCK.get(),
            "molten_emerald" to Blocks.EMERALD_BLOCK,
            "cursed_gold_fluid" to ModBlocks.CURSED_GOLD_BLOCK.get(),
        )
        for ((fluidName, block) in blockInputs) {
            meltingRecipe(provider, fluidName, block, 1)
        }

        // 石英 / 紫水晶：九个物品
        meltingRecipe(provider, "molten_quartz", Items.QUARTZ, 9)
        meltingRecipe(provider, "molten_amethyst", Items.AMETHYST_SHARD, 9)
    }

    /** 单条熔融配方：`count` 个 [input] → 1000 mB [fluidName] */
    private fun meltingRecipe(
        provider: RegistrumRecipeProvider,
        fluidName: String,
        input: ItemLike,
        count: Int,
    ) {
        val fluid = AddonFluids.byName(fluidName) ?: return
        SuperHeatingRecipe.builder()
            .requires(input, count)
            .transform(fluid.cauldron.get(), BUCKET)
            .save(provider, AnvilCraftFluid.of("super_heating/melting/${fluidName.removePrefix("molten_")}"))
    }

    // ───────────────────────── 时移：下界合金 → 远古残骸 ─────────────────────────

    /**
     * 「下界合金锭 + 熔岩 —时移— 远古残骸」
     * 与「下界合金碎片 + 熔融钨 —时移— 远古残骸」。
     *
     * 这是上游那条过程配方的流体版：上游是"钨块 + 下界合金碎片 → 注入 → 假时移 → 远古残骸"
     * （`ProceduralProcessRecipeLoader`），这里换成时移 + 我们的熔融钨。
     */
    private fun timeWarpRecipes(provider: RegistrumRecipeProvider) {
        TimeWarpRecipe.builder()
            .fluid(Blocks.LAVA_CAULDRON)
            .consume(BUCKET)
            .requires(Items.NETHERITE_INGOT)
            .result(Items.ANCIENT_DEBRIS)
            .save(provider, AnvilCraftFluid.of("time_warp/ancient_debris_from_netherite_ingot"))

        val tungsten = AddonFluids.byName("molten_tungsten") ?: return
        TimeWarpRecipe.builder()
            .fluid(tungsten.cauldron.get())
            .consume(BUCKET)
            .requires(Items.NETHERITE_SCRAP)
            .result(Items.ANCIENT_DEBRIS)
            .save(provider, AnvilCraftFluid.of("time_warp/ancient_debris_from_molten_tungsten"))
    }

    // ───────────────────── 多流体混合（大型炼药锅 + 铁砧） ─────────────────────

    /**
     * 大型炼药锅在铁砧撞击时执行（`LargeCauldronBlockEntity#tryProcessFluidMixingRecipe`，
     * 用 `getAllRecipesFor` 全量遍历、**不筛命名空间**，所以本模组的配方能直接生效）。
     *
     * - 熔融铁 + 熔融红宝石 → 熔融皇家钢（待按新口径修订：用户要"任意熔融宝石 + 钻石 + 熔融铁"，
     *   而 `fluid_mixing` 吃不了物品，需要另想办法）
     * - 熔融黄玉 10 mB + 熔融铁 1000 mB → 磁铁块
     */
    private fun fluidMixingRecipes(provider: RegistrumRecipeProvider) {
        val iron = AddonFluids.byName("molten_iron") ?: return
        val ruby = AddonFluids.byName("molten_ruby") ?: return
        val royalSteel = AddonFluids.byName("molten_royal_steel")

        if (royalSteel != null) {
            FluidMixingRecipe.builder()
                .requires(iron.source, BUCKET)
                .requires(ruby.source, BUCKET)
                .result(royalSteel.source, BUCKET)
                .save(provider, AnvilCraftFluid.of("fluid_mixing/molten_royal_steel"))
        }

        // 熔融黄玉 10 mB + 熔融铁 1000 mB → 磁铁块（用户指定量）
        AddonFluids.byName("molten_topaz")?.let { topaz ->
            FluidMixingRecipe.builder()
                .requires(topaz.source, 10)
                .requires(iron.source, BUCKET)
                .result(ModBlocks.MAGNET_BLOCK.get(), 1)
                .save(provider, AnvilCraftFluid.of("fluid_mixing/magnet_block"))
        }
    }

    // ───────────────────── 固液反应（炼药锅 + 铁砧） ─────────────────────

    /** 熔融流体 + 材料 → 方块（与"熔融"方向相反：把熔融液浇回方块） */
    private fun solidLiquidRecipes(provider: RegistrumRecipeProvider) {
        // 熔融石英 + 下界石英 → 石英块
        AddonFluids.byName("molten_quartz")?.let { quartz ->
            SolidLiquidRecipe.builder()
                .cauldron(quartz.source)
                .consume(BUCKET)
                .requires(Items.QUARTZ)
                .result(Blocks.QUARTZ_BLOCK)
                .save(provider, AnvilCraftFluid.of("solid_liquid/quartz_block_from_molten_quartz"))
        }

        // 熔融绿宝石 + 绿宝石 → 绿宝石块
        AddonFluids.byName("molten_emerald")?.let { emerald ->
            SolidLiquidRecipe.builder()
                .cauldron(emerald.source)
                .consume(BUCKET)
                .requires(Items.EMERALD)
                .result(Blocks.EMERALD_BLOCK)
                .save(provider, AnvilCraftFluid.of("solid_liquid/emerald_block_from_molten_emerald"))
        }

        // 熔融铁 + 铁锭 ×9 → 铁块
        AddonFluids.byName("molten_iron")?.let { iron ->
            SolidLiquidRecipe.builder()
                .cauldron(iron.source)
                .consume(BUCKET)
                .requires(Items.IRON_INGOT, 9)
                .result(Blocks.IRON_BLOCK)
                .save(provider, AnvilCraftFluid.of("solid_liquid/iron_block_from_molten_iron"))
        }
    }

    /** 产矿石配方里的**主流体**用量（熔融金属 / 熔融绿宝石），用户口径 250 mB */
    private const val ORE_FLUID = 250

    /** 宝石催化剂：产矿石的配方里红/蓝宝石只吃 10 mB（用户口径） */
    private const val GEM_CATALYST = 10

    /** 一桶 = 1000 mB */
    private const val BUCKET = 1000
}
