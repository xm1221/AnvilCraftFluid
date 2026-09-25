package cn.xm1221.AnvilCraftFluid.recipe

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.anvilcraft.lib.v2.recipe.InWorldRecipe
import dev.anvilcraft.lib.v2.recipe.outcome.IRecipeOutcome
import dev.anvilcraft.lib.v2.recipe.outcome.SpawnItem
import dev.anvilcraft.lib.v2.recipe.predicate.IRecipePredicate
import dev.anvilcraft.lib.v2.recipe.predicate.item.HasItemIngredient
import dev.anvilcraft.lib.v2.util.predicate.ChanceItemStack
import dev.anvilcraft.lib.v2.util.predicate.ItemIngredientPredicate
import dev.dubhe.anvilcraft.init.recipe.ModRecipeTriggers
import dev.dubhe.anvilcraft.recipe.anvil.predicate.block.HasCauldron
import dev.dubhe.anvilcraft.util.FluidStackPredicate
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

// ───────────────────────── 与上游一致的位置约定 ─────────────────────────
//
// 大型炼药锅在 `LargeCauldronBlockEntity#triggerOneRecipe` 里给出的配方位置是
// "铁砧落点上方基准点"（`new Vec3(helper.x + 0.5, base.y + 1.0, helper.z + 0.5)`，
// 其中 `base = 锅主部件.below()`），所以：
//   · 锅在配方位置**下方一格**；
//   · 输入物品在那口锅里；
//   · 产出物品落点同理。
// 这三组数值与上游 `SolidLiquidRecipe` / `SuperHeatingRecipe` / `TimeWarpRecipe`
// 的 `Property` 完全一致——不是拍脑袋定的，照抄即可。

/** 配方位置 → 大型炼药锅所在的方块位置 */
private val CAULDRON_OFFSET: Vec3 = Vec3(0.0, -1.0, 0.0)

/** 配方位置 → 锅内输入物品的检索点 */
private val ITEM_INPUT_OFFSET: Vec3 = Vec3(0.0, -0.375, 0.0)

/** 输入物品的检索范围（与 `Property` 默认给的一致） */
private val ITEM_INPUT_RANGE: Vec3 = Vec3(0.75, 0.75, 0.75)

/** 配方位置 → 产出物品的落点 */
private val ITEM_OUTPUT_OFFSET: Vec3 = Vec3(0.0, -0.75, 0.0)

/**
 * 本模组自研配方的**默认优先级**。
 *
 * `IPrioritized#compareTo` 是**数值越大越先执行**（它返回 `-compared`），
 * 而大型炼药锅是"按顺序找到第一条能匹配的配方就执行并结束"，
 * 所以专用配方必须压过通用的"熔液冷却成块"（`anvilcraft:solid_liquid`，
 * 自动优先级只有个位数），否则银块之类的通用配方会把矿石抢走。
 */
private const val SPECIFIC_RECIPE_PRIORITY: Int = 100

/**
 * **多流体 + 多物品 → 物品 / 流体**（本模组自己写的配方类型）。
 *
 * ## 字段
 *
 * | JSON 字段 | 作用 |
 * | --- | --- |
 * | `fluid_ingredients` | **输入流体**，可多条；每条形如 `{"fluid": "ns:id" 或 "#ns:tag", "amount": 1000}` |
 * | `ingredients` | **输入物品**，可多条（标准 `ItemIngredientPredicate`） |
 * | `fluid_results` | **产出流体**，可多条（标准 `FluidStack`） |
 * | `results` | **产出物品**，可多条（标准 `ChanceItemStack`，可带数量/概率） |
 * | `ignited` | 是否要求锅被点燃（默认 false） |
 * | `priority` / `max_efficiency` | 一般不用写，用默认值即可 |
 *
 * ## 为什么必须自己写
 *
 * 上游现成类型表达不了这种组合：
 * - `HasCauldronSimple#fluid` 是**单个** `FluidStackPredicate`，所以
 *   `time_warp` / `solid_liquid` / `super_heating` 都只吃**一种**锅里流体；
 * - `fluid_mixing` 虽然吃多种流体，但 `FluidMixingRecipe.Input` 只有流体、**没有物品槽**；
 * - 上游自己遇到这种组合时走的是硬编码代码类，压根没进配方系统。
 *
 * ## ⚠️ 为什么**不能**继承 `AbstractProcessRecipe`（踩过的坑，别再走回头路）
 *
 * 大型炼药锅的流体变动**不是立刻生效**的，它有一整套
 * "快照 → 模拟 → 提交" 机制（`LargeCauldronBlockEntity` 的
 * `fluidState` / `snapshotFluidRecipe` / `acceptFluidRecipe` / `commitFluidRecipes`）：
 *
 * 1. `matches()` 时 `HasCauldron#snapshot` 把当前流体**拷贝一份**存进上下文，
 *    之后所有消耗与产出都只在这份**工作副本**上做；
 * 2. `assemble()` 时 `HasCauldron#accept` 只是登记一个"提交"回调；
 * 3. `context.accept()` 才把工作副本**整体写回**锅
 *    （`cauldron.getMainPart().fluids.setFluids(工作副本)`）。
 *
 * 因此**任何绕过这套机制、直接改动 `cauldron.fluids` 的写法，都会被第 3 步整份覆盖掉**。
 * 之前两版自研实现正是踩在这里：
 * - 第一版把"扣额外流体 + 产出结果流体"挂在自定义 `IRecipeOutcome` 上；
 * - 第二版改到 `assemble()` 里直接 `drain` / `fill`。
 * 两版都是"立刻改"（`FluidAction.EXECUTE`），于是实机表现为
 * **主流体与输入物品照常被消耗、额外流体没被扣、产出流体也不见**——
 * 因为那两次改动在提交时被工作副本抹平了。
 *
 * 顺带更正一条旧结论：`InWorldRecipe#assemble` 里
 * `for (outcome : outcomes) outcome.acceptWithChance(context)` 是**会执行**的，
 * "挂成自定义产出不会被执行"是误判；改挂载位置当然不可能修好问题。
 *
 * 正确做法只有一条：**把每一种流体都表达成一个 `HasCauldron` 谓词**
 * （消耗写在 `consume`、产出写在 `transform`），让它们进入上面那套快照/提交机制。
 * 而 `AbstractProcessRecipe.Property` 只允许**一个** `hasCauldron`，
 * 所以本类型直接继承 [InWorldRecipe]，自己拼谓词表与产出表——这就是本类存在的理由。
 *
 * ## 两条硬约束（改本类时不要碰）
 *
 * 1. **输入物品必须用 `HasItemIngredient`**：大型炼药锅匹配物品配方时要走
 *    `recipeAnchoredByInput(recipe, stack)`，它只认 `HasItemIngredient` 这个类
 *    （自定义物品谓词不算），不满足就永远匹配不上。
 * 2. **必须有 `HasCauldron` 谓词**：物品槽为空时走 `isNotFluidOnlyRecipe(recipe)`，
 *    只有带 `HasCauldron` 的配方才不会被跳过（"没有物品输入"的配方全靠它）。
 *
 * 另外顺序上也有一条经验：**先排消耗、后排产出**。所有谓词共享同一份工作副本
 * 依次生效，先扣掉原料再放产物，才不会因为"某个罐子满了"而误判失败。
 */
class MultiFluidMixingRecipe(
    /** 输入物品 */
    val itemIngredients: List<ItemIngredientPredicate>,
    /** 产出物品 */
    val itemResults: List<ChanceItemStack>,
    /** 输入流体（至少一条） */
    val fluidIngredients: List<FluidRequirement>,
    /** 产出流体 */
    val fluidResults: List<FluidStack>,
    /** 是否要求锅被点燃 */
    val ignited: Boolean = false,
    priority: Int = SPECIFIC_RECIPE_PRIORITY,
    maxEfficiency: Int = Int.MAX_VALUE,
) : InWorldRecipe(
    iconOf(itemResults, fluidResults),
    ModRecipeTriggers.ON_ANVIL_FALL_ON.get(),
    // 冲突谓词 = 输入物品：`compatible = false`，所以它们是"互斥匹配"，
    // 多条物品要求会各自吃掉**不同的**物品，与上游 AbstractProcessRecipe 行为一致。
    itemPredicatesOf(itemIngredients),
    // 非冲突谓词 = 锅（每种流体一个）+ 产出流体
    cauldronPredicatesOf(fluidIngredients, fluidResults, ignited),
    itemOutcomesOf(itemResults),
    priority,
    false,
    maxEfficiency,
) {
    override fun getSerializer(): RecipeSerializer<MultiFluidMixingRecipe> =
        AddonRecipeTypes.MULTI_FLUID_MIXING_SERIALIZER.get()

    override fun getType(): RecipeType<MultiFluidMixingRecipe> =
        AddonRecipeTypes.MULTI_FLUID_MIXING_TYPE.get()

    /** 配方 JSON 的编解码器（`fluid_ingredients` 是本类型的输入流体列表） */
    class Serializer : RecipeSerializer<MultiFluidMixingRecipe> {
        override fun codec(): MapCodec<MultiFluidMixingRecipe> = CODEC

        override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, MultiFluidMixingRecipe> = STREAM_CODEC

        companion object {
            val CODEC: MapCodec<MultiFluidMixingRecipe> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    ItemIngredientPredicate.CODEC.listOf().optionalFieldOf("ingredients", listOf())
                        .forGetter(MultiFluidMixingRecipe::itemIngredients),
                    ChanceItemStack.CODEC.listOf().optionalFieldOf("results", listOf())
                        .forGetter(MultiFluidMixingRecipe::itemResults),
                    FluidRequirement.CODEC.listOf().fieldOf("fluid_ingredients")
                        .forGetter(MultiFluidMixingRecipe::fluidIngredients),
                    FluidStack.CODEC.listOf().optionalFieldOf("fluid_results", listOf())
                        .forGetter(MultiFluidMixingRecipe::fluidResults),
                    Codec.BOOL.optionalFieldOf("ignited", false)
                        .forGetter(MultiFluidMixingRecipe::ignited),
                    // ⚠️ `InWorldRecipe` 的取值器是 record 风格的方法（`priority()` /
                    //    `maxEfficiency()`），**没有** `getPriority()`；Kotlin 的方法引用
                    //    `MultiFluidMixingRecipe::priority` 会落到那个 private 字段上而编译不过，
                    //    所以这里一律用显式 lambda。
                    Codec.INT.optionalFieldOf("priority", SPECIFIC_RECIPE_PRIORITY)
                        .forGetter { recipe: MultiFluidMixingRecipe -> recipe.priority() },
                    Codec.INT.optionalFieldOf("max_efficiency", Int.MAX_VALUE)
                        .forGetter { recipe: MultiFluidMixingRecipe -> recipe.maxEfficiency() },
                ).apply(instance, ::create)
            }.validate(::validate)

            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MultiFluidMixingRecipe> = StreamCodec.of(
                { buf: RegistryFriendlyByteBuf, recipe: MultiFluidMixingRecipe -> encode(buf, recipe) },
                { buf: RegistryFriendlyByteBuf -> decode(buf) },
            )

            private fun create(
                itemIngredients: List<ItemIngredientPredicate>,
                itemResults: List<ChanceItemStack>,
                fluidIngredients: List<FluidRequirement>,
                fluidResults: List<FluidStack>,
                ignited: Boolean,
                priority: Int,
                maxEfficiency: Int,
            ): MultiFluidMixingRecipe = MultiFluidMixingRecipe(
                itemIngredients,
                itemResults,
                fluidIngredients,
                fluidResults,
                ignited,
                priority,
                maxEfficiency,
            )

            /**
             * 写坏的配方要在**加载期**就报出来，而不是等到实机里发现"配方怎么不生效"。
             *
             * ⚠️ 必须有输入流体：本类型的存在意义就是"多种流体"，而且
             * `HasCauldron` 谓词也靠它产生（见类注释里的两条硬约束）。
             */
            private fun validate(recipe: MultiFluidMixingRecipe): DataResult<MultiFluidMixingRecipe> {
                if (recipe.fluidIngredients.isEmpty()) {
                    return DataResult.error { "multi_fluid_mixing 配方必须至少有一种输入流体（fluid_ingredients）" }
                }
                if (recipe.itemResults.isEmpty() && recipe.fluidResults.isEmpty()) {
                    return DataResult.error { "multi_fluid_mixing 配方必须至少有一个产出（results / fluid_results）" }
                }
                return DataResult.success(recipe)
            }

            private fun encode(buf: RegistryFriendlyByteBuf, recipe: MultiFluidMixingRecipe) {
                ITEM_INGREDIENTS_STREAM.encode(buf, recipe.itemIngredients)
                ITEM_RESULTS_STREAM.encode(buf, recipe.itemResults)
                FLUID_INGREDIENTS_STREAM.encode(buf, recipe.fluidIngredients)
                FLUID_RESULTS_STREAM.encode(buf, recipe.fluidResults)
                buf.writeBoolean(recipe.ignited)
                buf.writeVarInt(recipe.priority())
                buf.writeVarInt(recipe.maxEfficiency())
            }

            private fun decode(buf: RegistryFriendlyByteBuf): MultiFluidMixingRecipe = MultiFluidMixingRecipe(
                ITEM_INGREDIENTS_STREAM.decode(buf),
                ITEM_RESULTS_STREAM.decode(buf),
                FLUID_INGREDIENTS_STREAM.decode(buf),
                FLUID_RESULTS_STREAM.decode(buf),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
            )

            private val ITEM_INGREDIENTS_STREAM =
                ItemIngredientPredicate.STREAM_CODEC.apply(ByteBufCodecs.list())
            private val ITEM_RESULTS_STREAM =
                ChanceItemStack.STREAM_CODEC.apply(ByteBufCodecs.list())
            private val FLUID_INGREDIENTS_STREAM =
                FluidRequirement.STREAM_CODEC.apply(ByteBufCodecs.list())
            private val FLUID_RESULTS_STREAM =
                FluidStack.STREAM_CODEC.apply(ByteBufCodecs.list())
        }
    }
}

// ───────────────────── 谓词/产出表（顶层私有，供超类构造器调用） ─────────────────────

/** 输入物品 → `HasItemIngredient`（⚠️ 必须是这个类，见类注释的硬约束 1） */
private fun itemPredicatesOf(items: List<ItemIngredientPredicate>): List<IRecipePredicate<*>> =
    items.map { HasItemIngredient.fromPredicate(it, ITEM_INPUT_OFFSET, ITEM_INPUT_RANGE) }

/**
 * 锅谓词表：**先消耗、后产出**。
 *
 * 每条输入流体一个 `HasCauldron`（消耗量写在 `consume`），每条产出流体一个
 * `HasCauldron`（产物写在 `transform`）。它们会一起进入大型炼药锅那套
 * 快照/提交机制，这才是流体真正被扣掉、被加进去的唯一正确途径。
 */
private fun cauldronPredicatesOf(
    ingredients: List<FluidRequirement>,
    results: List<FluidStack>,
    ignited: Boolean,
): List<IRecipePredicate<*>> = buildList<IRecipePredicate<*>> {
    ingredients.forEach { requirement -> add(requirement.toCauldron(ignited)) }
    results.forEach { result ->
        add(
            HasCauldron.builder()
                .offset(CAULDRON_OFFSET)
                // 只产出、不检验：`ANY` 让 `HasCauldron#hasCheck()` 为 false，
                // 于是它不去匹配任何已有流体，只管往锅里加；一条产物一个谓词
                // （单个 transform 不会触发 `supportsMultipleFluidOutputs` 检查）。
                .transform(result)
                .build(),
        )
    }
}

/** 产出物品 → `SpawnItem`（与上游 `Property#getOutcomes` 同一写法） */
private fun itemOutcomesOf(results: List<ChanceItemStack>): List<IRecipeOutcome<*>> =
    results.map { SpawnItem.fromChance(it, ITEM_OUTPUT_OFFSET) }

/**
 * 配方图标：优先产出物品 → 产出流体的桶 → 铁砧。
 *
 * `InWorldRecipe#getResultItem` 会返回它，配方书/JEI 之类偶尔会用到，
 * 所以尽量不要给空物品堆。
 */
private fun iconOf(items: List<ChanceItemStack>, fluids: List<FluidStack>): ItemStack {
    items.firstOrNull()?.let { chance -> return chance.stack().copyWithCount(1) }
    fluids.firstOrNull()?.fluid?.bucket?.takeIf { it != Items.AIR }?.let { return ItemStack(it) }
    return ItemStack(Items.ANVIL)
}

/**
 * 一条"锅里要有多少某流体"的要求。
 *
 * 自己写而不是直接用 `SizedFluidIngredient`，是为了让 `#标签` 写起来直白可控：
 * JSON 里就是 `{"fluid": "#anvilcraft_fluid:molten_gem", "amount": 1000}`，
 * 以 `#` 开头当标签、否则当具体流体 id——这样"任意熔融宝石"一条配方就够。
 *
 * 注意 `amount` 只是**消耗量**，它最终写进 `HasCauldron#consume`，
 * **不**写进流体谓词的 `amount` 上下界（那会变成"锅里存量必须正好这么多"，是另一个语义）。
 */
class FluidRequirement private constructor(
    val tag: TagKey<Fluid>?,
    val fluid: Fluid?,
    val amount: Int,
) {
    /** 供 JSON 写回用：标签带 `#` 前缀，普通流体写 id */
    fun id(): String =
        tag?.let { "#${it.location}" }
            ?: BuiltInRegistries.FLUID.getKey(fluid!!).toString()

    /** 转成上游流体谓词（具体流体或整个标签） */
    fun predicate(): FluidStackPredicate =
        tag?.let { FluidStackPredicate.builder().fluid(it).build() }
            ?: FluidStackPredicate.builder().fluid(fluid!!).build()

    /** 转成上游锅谓词：`consume` = 本要求的量 */
    fun toCauldron(ignited: Boolean): HasCauldron =
        HasCauldron.builder()
            .offset(CAULDRON_OFFSET)
            .fluid(predicate())
            .consume(amount)
            .apply { if (ignited) ignite() }
            .build()

    /** 所有可能满足该要求的流体（标签会展开成标签内容），展示用 */
    fun candidates(): List<Fluid> =
        tag?.let { key ->
            BuiltInRegistries.FLUID.getTag(key)
                .map { named -> named.map { holder -> holder.value() } }
                .orElse(emptyList())
        } ?: listOfNotNull(fluid)

    companion object {
        fun of(fluid: Fluid, amount: Int) = FluidRequirement(null, fluid, amount)
        fun of(tag: TagKey<Fluid>, amount: Int) = FluidRequirement(tag, null, amount)

        private fun parse(id: String, amount: Int): FluidRequirement =
            if (id.startsWith("#")) {
                of(TagKey.create(Registries.FLUID, ResourceLocation.parse(id.substring(1))), amount)
            } else {
                of(BuiltInRegistries.FLUID.get(ResourceLocation.parse(id)), amount)
            }

        val CODEC: Codec<FluidRequirement> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("fluid").forGetter(FluidRequirement::id),
                Codec.INT.optionalFieldOf("amount", 1000).forGetter(FluidRequirement::amount),
            ).apply(instance, ::parse)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, FluidRequirement> =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                FluidRequirement::id,
                ByteBufCodecs.VAR_INT,
                FluidRequirement::amount,
                ::parse,
            )
    }
}

/** 本模组自己注册的配方类型与序列化器 */
object AddonRecipeTypes {

    private val TYPES: DeferredRegister<RecipeType<*>> =
        DeferredRegister.create(Registries.RECIPE_TYPE, AnvilCraftFluid.MOD_ID)

    private val SERIALIZERS: DeferredRegister<RecipeSerializer<*>> =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, AnvilCraftFluid.MOD_ID)

    /**
     * 多流体 + 多物品 → 物品/流体。
     *
     * ⚠️ 1.21.1 的 `RecipeType` 是**接口**（不是上游源码里那种可 `new` 的类），
     * 实例要用 `RecipeType.simple(ResourceLocation)` 造；注册名由
     * `DeferredRegister` 决定，也就是 `anvilcraft_fluid:multi_fluid_mixing`。
     */
    val MULTI_FLUID_MIXING_TYPE: DeferredHolder<RecipeType<*>, RecipeType<MultiFluidMixingRecipe>> =
        TYPES.register(
            "multi_fluid_mixing",
            Supplier { RecipeType.simple(AnvilCraftFluid.of("multi_fluid_mixing")) },
        )

    val MULTI_FLUID_MIXING_SERIALIZER: DeferredHolder<RecipeSerializer<*>, RecipeSerializer<MultiFluidMixingRecipe>> =
        SERIALIZERS.register("multi_fluid_mixing", Supplier { MultiFluidMixingRecipe.Serializer() })

    /** 在 mod 构造阶段挂到 mod 事件总线 */
    fun register(bus: IEventBus) {
        TYPES.register(bus)
        SERIALIZERS.register(bus)
    }
}
