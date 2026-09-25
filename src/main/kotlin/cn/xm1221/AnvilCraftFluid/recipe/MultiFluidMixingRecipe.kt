package cn.xm1221.AnvilCraftFluid.recipe

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.anvilcraft.lib.v2.recipe.outcome.IRecipeOutcome
import dev.anvilcraft.lib.v2.recipe.util.InWorldRecipeContext
import dev.anvilcraft.lib.v2.util.predicate.ChanceItemStack
import dev.anvilcraft.lib.v2.util.predicate.ItemIngredientPredicate
import dev.dubhe.anvilcraft.api.fluid.LargeCauldronFluidHandler
import dev.dubhe.anvilcraft.block.entity.LargeCauldronBlockEntity
import dev.dubhe.anvilcraft.recipe.anvil.wrap.AbstractProcessRecipe
import dev.dubhe.anvilcraft.recipe.component.HasCauldronSimple
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * **多流体 + 物品 → 物品/流体**配方（本模组自己写的类型）。
 *
 * ## 为什么必须自己写
 *
 * 查证结论：AnvilCraft 现成的配方类型都表达不了"两种以上流体"——
 * - `HasCauldronSimple#fluid` 是**单个** `FluidStackPredicate`，所以
 *   `time_warp` / `solid_liquid` / `super_heating` 都只吃**一种**锅里流体；
 * - `fluid_mixing` 虽然吃多种流体，但 `FluidMixingRecipe.Input` 是
 *   `record Input(List<FluidStack> fluids)`——**没有物品槽**；
 * - 上游自己遇到这种组合（银粒 + 带魔咒的液态魔咒）时用的是硬编码代码类
 *   `LiquidEnchantmentCauldronRecipe`，根本没走配方系统。
 *
 * ## 怎么接进大型炼药锅
 *
 * 继承 [AbstractProcessRecipe]——它的构造器内部已经把
 * `ModRecipeTriggers.ON_ANVIL_FALL_ON` 触发器、谓词列表与产出列表交给
 * `InWorldRecipe` 基类，所以 `LargeCauldronBlockEntity#triggerOneRecipe`
 * 按触发器遍历时会**自动包含**本类型（它不筛命名空间、不筛类型）。
 *
 * | 字段 | 作用 |
 * | --- | --- |
 * | `ingredients` | 输入物品（可选；有它就走"物品锚定"匹配路径） |
 * | `results` | 输出物品 |
 * | `cauldron` | **主流体**要求，直接用上游标准的 [HasCauldronSimple]（含消耗量与转换） |
 * | `extra_fluids` | **额外流体**要求（本类型新增，支持 `#标签`） |
 * | `fluid_results` | 产出的流体（本类型新增） |
 *
 * ## ⚠️ 效果不能写在 `assemble()` 里
 *
 * 配方要同步到客户端、JEI/配方书也可能调用 `assemble()`——把扣流体写在那里会误扣。
 * 上游的规矩是：`assemble()` 只产出 `ItemStack`，副作用放在 [IRecipeOutcome] 里、
 * 由 `context.accept()` 在真正执行时触发。所以额外流体的扣除与产出都在
 * [ExtraFluidOutcome] 里做。
 *
 * ## ⚠️ 为什么 `cauldron` 字段不能省
 *
 * 大型炼药锅的匹配分两条路（`LargeCauldronBlockEntity#triggerOneRecipe`）：
 * - 输入槽**有**物品时，要求配方里有 `HasItemIngredient` 谓词（`recipeAnchoredByInput`）；
 * - 输入槽**为空**时，要求配方里有 `HasCauldron` 谓词（`isNotFluidOnlyRecipe`）。
 *
 * 所以"熔融金属 + 熔融宝石 → 矿石"这种**没有物品输入**的配方必须带一个
 * [HasCauldronSimple]，否则永远匹配不到（哪怕真正要扣的流体写在 `extra_fluids` 里）。
 */
class MultiFluidMixingRecipe(
    val itemIngredients: List<ItemIngredientPredicate>,
    val results: List<ChanceItemStack>,
    val cauldron: HasCauldronSimple,
    val extraFluids: List<FluidRequirement>,
    val fluidResults: List<FluidStack>,
    // ⚠️ 不要写成 `val maxEfficiency`——基类 AbstractProcessRecipe 已经有 `maxEfficiency()`，
    //    同名属性会让 `::maxEfficiency` 这种引用产生重载歧义（编译不过）。
    maxEfficiency: Int,
) : AbstractProcessRecipe<MultiFluidMixingRecipe>(
    Property()
        .setItemInputOffset(Vec3(0.0, -0.375, 0.0))
        .setItemInputRange(Vec3(0.75, 0.75, 0.75))
        .setInputItems(itemIngredients)
        .setItemOutputOffset(Vec3(0.0, -0.75, 0.0))
        .setResultItems(results)
        .setCauldronOffset(CAULDRON_OFFSET)
        .setHasCauldron(cauldron)
        // 专用配方要压过通用的"熔液冷却成块"（用户反馈：银块抢了矿石、块抢了浮霜流体）
        .setPriority(SPECIFIC_RECIPE_PRIORITY),
    maxEfficiency,
) {
    /**
     * ⚠️ 额外流体的**扣除与产出放在这里**，而不是挂成自定义产出（`Property#addOutcome`）。
     *
     * 实机反馈（2026-09-25）：挂成自定义 `IRecipeOutcome` 时，配方能匹配（主流体与输入物品
     * 都照常被消耗），但**额外流体没被扣走、产物也没进锅**——那条自定义产出根本没被执行。
     * `LargeCauldronBlockEntity#triggerOneRecipe` 明确会调用 `recipe.assemble(...)`，
     * 所以放在这里一定跑得到。
     *
     * 客户端没有锅的方块实体，[ExtraFluidOutcome] 取不到就返回，不会产生副作用。
     */
    override fun assemble(
        context: InWorldRecipeContext,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): ItemStack {
        ExtraFluidOutcome(extraFluids, fluidResults).accept(context)
        return super.assemble(context, registries)
    }
    override fun getSerializer(): RecipeSerializer<MultiFluidMixingRecipe> =
        AddonRecipeTypes.MULTI_FLUID_MIXING_SERIALIZER.get()

    override fun getType(): RecipeType<MultiFluidMixingRecipe> =
        AddonRecipeTypes.MULTI_FLUID_MIXING_TYPE.get()

    /** 标准检查（物品、主流体、铁砧等由 `Property` 的谓词完成）+ **额外流体**检查 */
    override fun matches(context: InWorldRecipeContext, level: Level): Boolean {
        if (!super.matches(context, level)) return false
        val fluids = cauldronAt(context) ?: return false
        return extraFluids.all { requirement -> fluids.totalMatching(requirement) >= requirement.amount }
    }

    /** 配方位置（铁砧落点）正下方那口锅 */
    internal fun cauldronAt(context: InWorldRecipeContext): LargeCauldronFluidHandler? =
        cauldronEntityAt(context)?.fluids

    internal fun cauldronEntityAt(context: InWorldRecipeContext): LargeCauldronBlockEntity? {
        val pos = BlockPos.containing(context.pos).offset(CAULDRON_OFFSET)
        return context.level.getBlockEntity(pos) as? LargeCauldronBlockEntity
    }

    class Serializer : RecipeSerializer<MultiFluidMixingRecipe> {
        override fun codec(): MapCodec<MultiFluidMixingRecipe> = CODEC

        override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, MultiFluidMixingRecipe> = STREAM_CODEC

        companion object {
            val CODEC: MapCodec<MultiFluidMixingRecipe> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    ItemIngredientPredicate.CODEC.listOf().fieldOf("ingredients")
                        .forGetter(MultiFluidMixingRecipe::itemIngredients),
                    ChanceItemStack.CODEC.listOf().fieldOf("results")
                        .forGetter(MultiFluidMixingRecipe::results),
                    HasCauldronSimple.CODEC.fieldOf("cauldron")
                        .forGetter(MultiFluidMixingRecipe::cauldron),
                    FluidRequirement.CODEC.listOf().optionalFieldOf("extra_fluids", listOf())
                        .forGetter(MultiFluidMixingRecipe::extraFluids),
                    FluidStack.CODEC.listOf().optionalFieldOf("fluid_results", listOf())
                        .forGetter(MultiFluidMixingRecipe::fluidResults),
                    Codec.INT.optionalFieldOf("max_efficiency", Int.MAX_VALUE)
                        .forGetter(MultiFluidMixingRecipe::maxEfficiency),
                ).apply(instance) { ingredients, results, cauldron, extra, produced, maxEfficiency ->
                    MultiFluidMixingRecipe(ingredients, results, cauldron, extra, produced, maxEfficiency)
                }
            }

            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MultiFluidMixingRecipe> =
                StreamCodec.composite(
                    ItemIngredientPredicate.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MultiFluidMixingRecipe::itemIngredients,
                    ChanceItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MultiFluidMixingRecipe::results,
                    HasCauldronSimple.STREAM_CODEC,
                    MultiFluidMixingRecipe::cauldron,
                    FluidRequirement.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MultiFluidMixingRecipe::extraFluids,
                    FluidStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MultiFluidMixingRecipe::fluidResults,
                    ByteBufCodecs.INT,
                    MultiFluidMixingRecipe::maxEfficiency,
                ) { ingredients, results, cauldron, extra, produced, maxEfficiency ->
                    MultiFluidMixingRecipe(ingredients, results, cauldron, extra, produced, maxEfficiency)
                }
        }
    }

    companion object {
        /** 与上游固液反应同一套偏移：铁砧落点下方一格是锅 */
        private val CAULDRON_OFFSET: Vec3i = Vec3i(0, -1, 0)

        /** 本模组自研配方都属于"专用"配方，优先级给高值 */
        private const val SPECIFIC_RECIPE_PRIORITY: Int = 100
    }
}

/**
 * 一条"锅里要有多少某流体"的要求。
 *
 * 自己写而不是用 `SizedFluidIngredient`，是为了让 `#标签` 写起来直白可控：
 * JSON 里就是 `{"fluid": "#anvilcraft_fluid:molten_gem", "amount": 1000}`，
 * 以 `#` 开头当标签、否则当具体流体 id——这样"任意熔融宝石"一条配方就够。
 */
class FluidRequirement private constructor(
    val tag: TagKey<Fluid>?,
    val fluid: Fluid?,
    val amount: Int,
) {
    fun test(stack: FluidStack): Boolean {
        if (stack.isEmpty) return false
        val registered = stack.fluid
        return if (tag != null) registered.builtInRegistryHolder().`is`(tag) else registered === fluid
    }

    /** 供 JSON 写回用：标签带 `#` 前缀，普通流体写 id */
    fun id(): String =
        tag?.let { "#${it.location}" }
            ?: BuiltInRegistries.FLUID.getKey(fluid!!).toString()

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

/**
 * 额外流体的扣除与产出。
 *
 * 副作用只在 `context.accept()` 时发生（见 [MultiFluidMixingRecipe] 的说明）。
 * 主流体由 [HasCauldronSimple] 那套标准机制处理，这里只管 `extra_fluids` 与
 * `fluid_results`，避免两处逻辑重叠。
 */
class ExtraFluidOutcome(
    private val requirements: List<FluidRequirement>,
    private val produced: List<FluidStack>,
) : IRecipeOutcome<ExtraFluidOutcome> {

    override fun accept(context: InWorldRecipeContext) {
        val pos = BlockPos.containing(context.pos).offset(Vec3i(0, -1, 0))
        val cauldron = context.level.getBlockEntity(pos) as? LargeCauldronBlockEntity ?: return
        val fluids = cauldron.fluids
        requirements.forEach { requirement -> fluids.drainMatching(requirement) }
        produced.forEach { fluid -> fluids.fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE) }
    }

    override fun getType(): IRecipeOutcome.Type<ExtraFluidOutcome> = TYPE

    companion object {
        /**
         * `IRecipeOutcome` 的接口要求返回一个 `Type`。
         *
         * 本模组的配方由自己的 [MultiFluidMixingRecipe.Serializer] 完整序列化
         * （额外流体走 `extra_fluids` 字段），**不会**经过 `IRecipeOutcome` 的派发编解码器，
         * 所以这里只是把接口契约实现完整——真被调用也能正常工作。
         */
        val TYPE: IRecipeOutcome.Type<ExtraFluidOutcome> = object : IRecipeOutcome.Type<ExtraFluidOutcome> {
            override fun codec(): MapCodec<ExtraFluidOutcome> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    FluidRequirement.CODEC.listOf().fieldOf("extra_fluids")
                        .forGetter(ExtraFluidOutcome::requirements),
                    FluidStack.CODEC.listOf().optionalFieldOf("fluid_results", listOf())
                        .forGetter(ExtraFluidOutcome::produced),
                ).apply(instance) { requirements, produced -> ExtraFluidOutcome(requirements, produced) }
            }

            override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, ExtraFluidOutcome> =
                StreamCodec.composite(
                    FluidRequirement.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    ExtraFluidOutcome::requirements,
                    FluidStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    ExtraFluidOutcome::produced,
                ) { requirements, produced -> ExtraFluidOutcome(requirements, produced) }
        }
    }
}

/** 锅里满足该要求的流体总量（mB） */
internal fun LargeCauldronFluidHandler.totalMatching(requirement: FluidRequirement): Int {
    var total = 0
    for (tank in 0 until tanks) {
        val stored = getFluidInTank(tank)
        if (!stored.isEmpty && requirement.test(stored)) total += stored.amount
    }
    return total
}

/** 扣掉满足 [requirement] 的量，返回实际扣掉的 mB */
internal fun LargeCauldronFluidHandler.drainMatching(requirement: FluidRequirement): Int {
    var remaining = requirement.amount
    var drained = 0
    for (tank in 0 until tanks) {
        if (remaining <= 0) break
        val stored = getFluidInTank(tank)
        if (stored.isEmpty || !requirement.test(stored)) continue
        val request = FluidStack(stored.fluid, minOf(remaining, stored.amount))
        val got = drainStoredFluid(request, IFluidHandler.FluidAction.EXECUTE)
        remaining -= got.amount
        drained += got.amount
    }
    return drained
}

/** 本模组自己注册的配方类型与序列化器 */
object AddonRecipeTypes {

    private val TYPES: DeferredRegister<RecipeType<*>> =
        DeferredRegister.create(Registries.RECIPE_TYPE, AnvilCraftFluid.MOD_ID)

    private val SERIALIZERS: DeferredRegister<RecipeSerializer<*>> =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, AnvilCraftFluid.MOD_ID)

    /**
     * 多流体 + 物品 → 物品/流体。
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
