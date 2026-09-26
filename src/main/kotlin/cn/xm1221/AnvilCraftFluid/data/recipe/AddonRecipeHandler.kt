package cn.xm1221.AnvilCraftFluid.data.recipe

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import dev.anvilcraft.lib.v2.registrum.providers.RegistrumRecipeProvider
import cn.xm1221.AnvilCraftFluid.init.AddonFluidTags
import cn.xm1221.AnvilCraftFluid.recipe.FluidRequirement
import cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe
import dev.anvilcraft.lib.v2.util.predicate.ChanceItemStack
import dev.anvilcraft.lib.v2.util.predicate.ItemIngredientPredicate
import net.neoforged.neoforge.fluids.FluidStack
import dev.dubhe.anvilcraft.init.block.ModBlocks
import dev.dubhe.anvilcraft.init.block.ModFluids
import dev.dubhe.anvilcraft.init.item.ModItems
import dev.dubhe.anvilcraft.recipe.anvil.util.WrapUtils
import dev.dubhe.anvilcraft.recipe.anvil.wrap.SolidLiquidRecipe
import dev.dubhe.anvilcraft.recipe.anvil.wrap.SuperHeatingRecipe
import dev.dubhe.anvilcraft.recipe.anvil.wrap.TimeWarpRecipe
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Items
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import javax.print.attribute.standard.MediaSize

/**
 * 配方 datagen。
 *
 * ## 五套机制（都跑在 AnvilCraft 的机器上，各自能表达的东西不一样）
 *
 * | 类型 | 输入 | 输出 | 本模组用来做什么 |
 * | --- | --- | --- | --- |
 * | `anvilcraft:super_heating` 高温熔炼 | **1 种锅里流体** + 物品 | 物品 / 流体（`.transform(锅, mB)`） | **熔融**：方块或 9 物品 → 一桶熔融流体 |
 * | `anvilcraft:time_warp` 时移 | **1 种锅里流体** + 物品 | 物品 / 流体 | 下界合金 → 远古残骸；**浮霜流体 + 皇家钢 → 浮霜金属** |
 * | `anvilcraft:solid_liquid` 固液反应 | **1 种锅里流体** + 物品 | 物品 / 流体 | 熔融流体 + 材料 → 方块、熔液冷却成块 |
 * | `anvilcraft_fluid:multi_fluid_mixing` | **多种流体 + 多个物品** | **物品 / 流体** | **熔融皇家钢、浮霜/余烬流体、矿石、磁铁块**（见下） |
 *
 * ⚠️ 上游还有个 `anvilcraft:fluid_mixing`（多流体、不吃物品），**本模组一律不用它**：
 * 藿香没给那个类型注册手册展示组件，`<recipe>` 在手册里画出来是**空白**
 * （详见下面「多流体混合」小节留的那段说明）。
 *
 * ## ⚠️ 上游"锅 + 物品"类的配方**只能有一种流体**
 *
 * `HasCauldronSimple#fluid` 是**单个** `FluidStackPredicate`——所以"时移/固液/高温"
 * 都做不出"两种流体"的配方。这就是"熔融宝石 + 钻石 + 熔融铁 → 熔融皇家钢"
 * （两流体 + 一物品）必须走本模组自研类型的理由，它与上面四套的分工见
 * [MultiFluidMixingRecipe] 的类注释。
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
        multiFluidMixingRecipes(provider)
        coolingRecipes(provider)
        solidLiquidRecipes(provider)
        otherRecipes(provider)
    }

    // ─────────────── 自定义类型：多流体 + 物品 → 物品/流体 ───────────────

    /**
     * 这些配方用**本模组自己的配方类型** `anvilcraft_fluid:multi_fluid_mixing`
     * （见 [cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe]），
     * 因为上游现成类型都表达不了"多种流体"或"多流体 + 物品"：
     * 锅+物品类只能带一种锅里流体；`fluid_mixing` 既没有物品槽，**手册里又画不出来**。
     *
     * - **熔融皇家钢**：熔融铁 + 任意熔融宝石（红/黄/蓝/绿）+ 钻石 → 熔融皇家钢
     * - **功能流体**：浮霜流体＝细雪 1000 + 浮霜金属粒 1；
     *   余烬流体＝原油 1000 + 熔岩 1000 + 余烬金属粒 1
     * - **矿石**：熔融红宝石 10 + 熔融金属 250 → 对应**深层**矿石；
     *   熔融蓝宝石 10 + 熔融金属 250 → 对应**普通**矿石
     *   （⚠️ AnvilCraft 的金属**只有深层矿石**，没有普通矿石版本，
     *    所以熔融钨只有深层那一侧；原版铁/金/铜两种都有）
     * - **磁铁块**：熔融黄玉 10 + 熔融铁 1000 → 磁铁块
     *   （用不上物品槽，本来上游 `fluid_mixing` 就够，但那个类型手册画不出来，
     *    理由见下方「多流体混合」小节的说明）
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
                fluidIngredients = listOf(
                    FluidRequirement.of(iron.source, BUCKET),
                    FluidRequirement.of(AddonFluidTags.ROYAL_STEEL_GEMS, BUCKET),
                ),
                fluidResults = listOf(FluidStack(royalSteel.source, BUCKET)),
            )
        }

        val powderSnow = powderSnowFluid()
        val frost = AddonFluids.byName(FROST_FLUID)
        if (powderSnow != null && frost != null) {
            multiFluid(
                provider,
                "multi_fluid_mixing/frost_fluid",
                items = listOf(
                    ItemIngredientPredicate.Builder.item().of(ModItems.FROST_METAL_NUGGET).build(),
                ),
                results = emptyList(),
                fluidIngredients = listOf(FluidRequirement.of(powderSnow, BUCKET)),
                fluidResults = listOf(FluidStack(frost.source, BUCKET)),
            )
        }

        // 余烬流体：1000 mB 原油 + 1000 mB 熔岩 + 1 余烬金属粒 → 1000 mB 余烬流体（用户口径）
        // 原油＝上游 `anvilcraft:oil`，只有 `ModFluids.OIL` 这个注册项可取（没有现成 Fluid 常量）；
        // 熔岩直接用原版 `Fluids.LAVA`。
        AddonFluids.byName(EMBER_FLUID)?.let { ember ->
            multiFluid(
                provider,
                "multi_fluid_mixing/ember_fluid",
                items = listOf(
                    ItemIngredientPredicate.Builder.item().of(ModItems.EMBER_METAL_NUGGET).build(),
                ),
                results = emptyList(),
                fluidIngredients = listOf(
                    FluidRequirement.of(ModFluids.OIL.get(), BUCKET),
                    FluidRequirement.of(Fluids.LAVA, BUCKET),
                ),
                fluidResults = listOf(FluidStack(ember.source, BUCKET)),
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
                fluidIngredients = listOf(
                    FluidRequirement.of(ruby.source, GEM_CATALYST),
                    FluidRequirement.of(metal.source, ORE_FLUID),
                ),
                fluidResults = emptyList(),
            )

            if (normal != null) {
                multiFluid(
                    provider,
                    "multi_fluid_mixing/ore/$metalName",
                    items = emptyList(),
                    results = listOf(ChanceItemStack.of(normal, 1)),
                    fluidIngredients = listOf(
                        FluidRequirement.of(sapphire.source, GEM_CATALYST),
                        FluidRequirement.of(metal.source, ORE_FLUID),
                    ),
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
                fluidIngredients = listOf(
                    FluidRequirement.of(sapphire.source, GEM_CATALYST),
                    FluidRequirement.of(emerald.source, ORE_FLUID),
                ),
                fluidResults = emptyList(),
            )
            multiFluid(
                provider,
                "multi_fluid_mixing/deepslate_emerald_ore",
                items = emptyList(),
                results = listOf(ChanceItemStack.of(Items.DEEPSLATE_EMERALD_ORE, 1)),
                fluidIngredients = listOf(
                    FluidRequirement.of(ruby.source, GEM_CATALYST),
                    FluidRequirement.of(emerald.source, ORE_FLUID),
                ),
                fluidResults = emptyList(),
            )
        }

        // 熔融黄玉 10 mB + 熔融铁 1000 mB → 磁铁块
        AddonFluids.byName("molten_topaz")?.let { topaz ->
            multiFluid(
                provider,
                "multi_fluid_mixing/magnet_block",
                items = emptyList(),
                results = listOf(ChanceItemStack.of(ModBlocks.MAGNET_BLOCK.get(), 1)),
                fluidIngredients = listOf(
                    FluidRequirement.of(topaz.source, GEM_CATALYST),
                    FluidRequirement.of(iron.source, BUCKET),
                ),
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

    /**
     * 交给本模组自己的配方类型（`provider` 就是 `RecipeOutput`，直接 accept 即可）。
     *
     * `fluidIngredients` 是**输入流体列表**：里面每一项都会变成一个 `HasCauldron`
     * 谓词，从而进入大型炼药锅那套"快照 → 提交"机制（详见
     * [cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe] 的类注释）。
     * 列表顺序 = 图上与匹配上的先后，一般"催化剂在前、主料在后"即可。
     */
    private fun multiFluid(
        provider: RegistrumRecipeProvider,
        path: String,
        items: List<ItemIngredientPredicate>,
        results: List<ChanceItemStack>,
        fluidIngredients: List<FluidRequirement>,
        fluidResults: List<FluidStack>,
    ) {
        provider.accept(
            AnvilCraftFluid.of(path),
            MultiFluidMixingRecipe(items, results, fluidIngredients, fluidResults),
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

            /*宝石块的配方冲突，删除
            "molten_ruby" to ModBlocks.RUBY_BLOCK.get(),
            "molten_sapphire" to ModBlocks.SAPPHIRE_BLOCK.get(),
            "molten_topaz" to ModBlocks.TOPAZ_BLOCK.get(),
            "molten_emerald" to Blocks.EMERALD_BLOCK,*/

            "cursed_gold_fluid" to ModBlocks.CURSED_GOLD_BLOCK.get(),
        )
        for ((fluidName, block) in blockInputs) {
            meltingRecipe(provider, fluidName, block, 1)
        }

        // ：九个物品
        meltingRecipe(provider, "molten_quartz", Items.QUARTZ, 9)
        meltingRecipe(provider, "molten_amethyst", Items.AMETHYST_SHARD, 9)
        meltingRecipe(provider,"molten_ruby",ModItems.RUBY, 9)
        meltingRecipe(provider,"molten_sapphire", ModItems.SAPPHIRE,9)
        meltingRecipe(provider,"molten_topaz", ModItems.TOPAZ,9)
        meltingRecipe(provider,"molten_emerald", Items.EMERALD,9)

        //TODO:添加九个锭到熔融金属的配方，由各种熔融宝石到熔融宝石的多流体混合配方,正统的用熔融宝石合成熔融皇家钢的配方
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

        // 浮霜金属：满锅浮霜流体 + 皇家钢（块/锭/粒）—时移— 浮霜金属（块/锭/粒）（用户口径）
        // ⚠️ 用户要求**移除**原来那条"不放物品、满锅浮霜流体 → 浮霜金属块"，
        //    改成"一锅浮霜流体 + 1 皇家钢"；块/锭/粒三档都要有，所以这里是三条。
        AddonFluids.byName(FROST_FLUID)?.let { frost ->
            val frostMetals = listOf<Triple<ItemLike, ItemLike, String>>(
                Triple(ModBlocks.ROYAL_STEEL_BLOCK.get(), ModBlocks.FROST_METAL_BLOCK.get(), "block"),
                Triple(ModItems.ROYAL_STEEL_INGOT.get(), ModItems.FROST_METAL_INGOT.get(), "ingot"),
                Triple(ModItems.ROYAL_STEEL_NUGGET.get(), ModItems.FROST_METAL_NUGGET.get(), "nugget"),
            )
            for ((steel, metal, suffix) in frostMetals) {
                TimeWarpRecipe.builder()
                    .fluid(frost.cauldron.get())
                    .consume(BUCKET)
                    .requires(steel)
                    .result(metal)
                    .save(provider, AnvilCraftFluid.of("time_warp/frost_metal_$suffix"))
            }
        }

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
     * ## ⚠️ 为什么这里**一条配方都没有**：上游 `fluid_mixing` 手册里画不出来
     *
     * 这条"熔融黄玉 10 + 熔融铁 1000 → 磁铁块"原先用上游的 `anvilcraft:fluid_mixing`
     * （`FluidMixingRecipe`）写。那个类型**游戏里是好用的**：
     * `LargeCauldronBlockEntity#tryProcessFluidMixingRecipe` 用 `getAllRecipesFor` 全量遍历、
     * 不筛命名空间，所以外模组的配方照样生效。
     *
     * 问题出在**手册**：藿香按**配方类型**注册展示组件
     * （`AnvilCraftRecipeComponentFactories` 里 23 个，见本模组
     * [cn.xm1221.AnvilCraftFluid.client.AddonAgeratumGuideRecipes] 的类注释），
     * 而 `fluid_mixing` **不在那 23 个里面**——上游自己也从不用 `<recipe>` 展示这类配方
     * （`assets/anvilcraft/ageratum` 里搜不到任何 `fluid_mixing`），只在 JEI 里给了分类。
     * 于是手册里那句 `<recipe id="…/fluid_mixing/magnet_block"/>` 渲染出来是**一片空白**
     * （用户实机发现）。
     *
     * 所以**本模组一律不给配方用上游 `fluid_mixing`**，全部走自研类型
     * [cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe]（图能画、还能带物品槽）。
     *
     * ⚠️ 这里**曾经**还有一条"熔融铁 + 熔融红宝石 → 熔融皇家钢"的旧配方，
     * 也已删除：皇家钢的新口径是"任意红/黄/蓝/绿熔融宝石 + **钻石** + 熔融铁"，
     * `fluid_mixing` 吃不了物品，留着只会多出一条不该存在的配方（实机已发现）。
     */




    private fun solidLiquidRecipes(provider: RegistrumRecipeProvider) {
    }

    private fun otherRecipes(provider: RegistrumRecipeProvider) {

        AddonFluids.byName("redstone_resin")?.cauldron?.let {
            SuperHeatingRecipe.builder()
                .fluid(Fluids.WATER)
                .requires(ModBlocks.RESIN_BLOCK)
                .requires(Items.REDSTONE,3)
                .transform(it.get(),BUCKET)
                .save(provider, AnvilCraftFluid.of("super_heating/redstone_resin"))
        }


    }
    /**
     * 细雪流体。
     *
     * 原版/NeoForge 没有给细雪暴露可直接引用的 `Fluid` 常量（上游 AnvilCraft 同样如此），
     * 所以照它的写法从**细雪炼药锅**反查：`WrapUtils.cauldron2Fluid`。
     *
     * ⚠️ **绝不能把异常漏出去**：本方法是在 `GatherDataEvent` 的监听器里被调用的
     * （`AddonRecipeHandler.init` 在监听时立即执行），一旦抛出，整个 datagen 会
     * **一个 provider 都注册不上**——日志只显示 `All providers took: 0 ms`、
     * `total files: 0`，而且 `src/generated` 里已生成的资源会被当成 stale 清空，
     * 偏偏 `runData` 还报 BUILD SUCCESSFUL（异常被事件派发吞掉）。已踩过一次。
     */
    private fun powderSnowFluid(): Fluid? =
        try {
            val id = WrapUtils.cauldron2Fluid(Blocks.POWDER_SNOW_CAULDRON)
            BuiltInRegistries.FLUID.get(id).takeIf { it !== Fluids.EMPTY }
        } catch (e: Throwable) {
            AnvilCraftFluid.LOGGER.warn("取不到细雪流体，跳过浮霜流体配方", e)
            null
        }

    /** 浮霜流体（功能性流体，见 `fluid/FluidSpec.kt`） */
    private const val FROST_FLUID = "frost_fluid"

    /** 余烬流体（功能性流体，见 `fluid/FluidSpec.kt`） */
    private const val EMBER_FLUID = "ember_fluid"

    /** 产矿石配方里的**主流体**用量（熔融金属 / 熔融绿宝石），用户口径 250 mB */
    private const val ORE_FLUID = 250

    /** 宝石催化剂：产矿石的配方里红/蓝宝石只吃 10 mB（用户口径） */
    private const val GEM_CATALYST = 10

    /** 一桶 = 1000 mB */
    private const val BUCKET = 1000
}
