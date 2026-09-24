package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid.Companion.REGISTRUM
import cn.xm1221.AnvilCraftFluid.block.AddonCauldronBlock
import cn.xm1221.AnvilCraftFluid.block.ReactiveLiquidBlock
import cn.xm1221.AnvilCraftFluid.block.ReforgingFluidBlock
import cn.xm1221.AnvilCraftFluid.fluid.AddonFluidSpecs
import cn.xm1221.AnvilCraftFluid.fluid.FluidFamily
import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec
import cn.xm1221.AnvilCraftFluid.fluid.WaterReactions
import dev.anvilcraft.lib.v2.registrum.builders.FluidBuilder
import dev.anvilcraft.lib.v2.registrum.providers.RegistrumBlockstateProvider
import dev.anvilcraft.lib.v2.registrum.util.entry.BlockEntry
import dev.anvilcraft.lib.v2.registrum.util.entry.FluidEntry
import net.minecraft.core.cauldron.CauldronInteraction
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.pathfinder.PathType
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
import net.neoforged.neoforge.client.model.generators.ConfiguredModel
import net.neoforged.neoforge.client.model.generators.ModelFile
import net.neoforged.neoforge.common.SoundActions
import net.neoforged.neoforge.fluids.BaseFlowingFluid
import net.neoforged.neoforge.fluids.FluidType
import java.util.function.Consumer

/**
 * 熔融流体的注册引擎。
 *
 * 每种流体一次注册出以下内容，全部遵循 `<name>` 命名约定：
 *
 * | 注册项 | 注册名 | 说明 |
 * | --- | --- | --- |
 * | 流体类型 | `anvilcraft_fluid:<name>`（FLUID_TYPES） | [tintedFluidType] 提供灰度贴图 + [FluidSpec.tint] 染色 |
 * | 源流体 / 流动流体 | `anvilcraft_fluid:<name>` / `flowing_<name>` | Registrum `FluidBuilder` 自动处理 |
 * | 世界流体方块 | `anvilcraft_fluid:<name>` | `LiquidBlock`；[FluidSpec.placeable] 为 false 时不注册 |
 * | 桶 | `anvilcraft_fluid:<name>_bucket` | `BucketItem`，贴图 `textures/item/<name>_bucket.png`（灰度 + 物品染色） |
 * | 炼药锅 | `anvilcraft_fluid:<name>_cauldron` | **[AddonCauldronBlock]，名字不能改**——AnvilCraft 的 `HasCauldron#getDefaultCauldron` 按名字推导 |
 *
 * 锅↔流体的对应关系在 [cn.xm1221.AnvilCraftFluid.event.CauldronFluidContentRegistry] 通过 NeoForge 的
 * `RegisterCauldronFluidContentEvent` 登记，这样 `CauldronUtil` 的层数换算、流体网络识别、
 * JEI 固液反应页都会自动认我们的锅。
 */
object AddonFluids {

    /**
     * 一种已注册的流体及其配套设施。
     *
     * @property spec 定义（名字、染色、物理参数）
     * @property fluid 流体条目（`source` 拿源流体，`bucket` 拿桶物品）
     * @property cauldron 炼药锅方块条目
     * @property interactions 该锅的交互表；桶相关的条目在
     *   [cn.xm1221.AnvilCraftFluid.event.CauldronInteractions] 里等注册表填充后再补进去
     */
    class RegisteredFluid(
        val spec: FluidSpec,
        val fluid: FluidEntry<BaseFlowingFluid.Flowing>,
        val cauldron: BlockEntry<AddonCauldronBlock>,
        val interactions: CauldronInteraction.InteractionMap,
    ) {
        /**
         * **源流体**，即注册名 `anvilcraft_fluid:<name>` 的那一个。
         *
         * ⚠️ 不要直接用 [fluid]`.get()`——Registrum 的 `FluidBuilder` 把 **流动** 流体
         * 作为主条目（`flowing_<name>`），源流体挂在它的 `getSource()` 上。
         * 配方、桶、炼药锅内容、比较/匹配一律要用源流体，
         * 否则会得到 `anvilcraft_fluid:flowing_xxx` 这种永远匹配不上的 id。
         *
         * ⚠️⚠️ **绝对不要写 `fluid.getSource<BaseFlowingFluid.Flowing>()`**！
         * `FluidEntry.getSource<S>()` 的实现是 `(S) get().getSource()`——一次 **无检查强转**。
         * 传 `Flowing` 作为类型实参时，运行时会把 `BaseFlowingFluid$Source` 往
         * `BaseFlowingFluid$Flowing` 转，直接
         * `ClassCastException: Source cannot be cast to Flowing`
         * （2026-09-24 的崩溃就是这么来的：空桶从熔融金属锅里舀出时炸在交互 lambda 里）。
         * 走 `get().source` 用的是原版 `FlowingFluid#getSource(): Fluid`，**没有任何泛型转换**。
         */
        val source: Fluid get() = fluid.get().source

        /** 桶物品；注册冻结后才可安全解析 */
        val bucket: Item? get() = source.bucket

        /** 该流体在锅里的容量（mB）；本项目统一用"满锅"实现，恒为 1000 */
        val cauldronAmount: Int get() = 1000

        /** 层数属性；"满锅"实现没有层数状态，故为 null */
        val levelProperty: IntegerProperty? get() = null
    }

    /** 全部已注册流体（顺序与 [AddonFluidSpecs.ALL] 一致） */
    val REGISTERED: List<RegisteredFluid> = AddonFluidSpecs.ALL.map(::registerOne)

    /**
     * 触发本 object 初始化。必须在 mod 构造阶段调用
     * （[AnvilCraftFluid] 的 `init` 块已调用）。
     */
    fun register() {
        val reactive = REGISTERED.count { WaterReactions.isReactive(it.spec.name) }
        AnvilCraftFluid.LOGGER.debug(
            "Registered {} AnvilCraft fluid(s), {} of them water-reactive (custom ReactiveLiquidBlock)",
            REGISTERED.size,
            reactive,
        )
    }

    /** 按名字查已注册流体（供事件与配方代码使用） */
    fun byName(name: String): RegisteredFluid? = REGISTERED.firstOrNull { it.spec.name == name }

    /** 该流体是否属于本模组（按源流体比对） */
    fun isAddonFluid(fluid: Fluid): Boolean = REGISTERED.any { it.source === fluid }

    // ───────────────────────────── 内部实现 ─────────────────────────────

    private fun registerOne(spec: FluidSpec): RegisteredFluid {
        // 1) 先建交互表对象，内容稍后再填（交互只在运行时被查询）
        val interactions = CauldronInteraction.newInteractionMap("${spec.name}_cauldron")

        // 2) 注册流体本体（连带 `<name>` 液体方块与 `<name>_bucket` 桶）
        val fluid = registerFluid(spec)

        // 3) 空桶从锅里舀出：直接用原版 CauldronInteraction.fillBucket。
        //    把桶倒进锅由 NeoForge 的 CauldronFluidContent 自动处理（见注册事件）。
        //    ⚠️ 取桶物品必须走 `get().source`（原版 `FlowingFluid#getSource(): Fluid`），
        //       不能写 `getSource<BaseFlowingFluid.Flowing>()`——那是无检查强转，
        //       运行时会把 `Source` 转 `Flowing` 抛 ClassCastException（空桶舀出时崩溃的直接原因）。
        interactions.map()[Items.BUCKET] = CauldronInteraction { state, level, pos, player, hand, stack ->
            val filled = fluid.get().source.bucket?.let(::ItemStack) ?: ItemStack.EMPTY
            if (filled.isEmpty) {
                ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
            } else {
                CauldronInteraction.fillBucket(
                    state,
                    level,
                    pos,
                    player,
                    hand,
                    stack,
                    filled,
                    { true },
                    SoundEvents.BUCKET_FILL_LAVA,
                )
            }
        }

        // 4) 注册炼药锅方块（名字必须是 `<name>_cauldron`）
        val cauldron = registerCauldron(spec, interactions, isReforging(spec))

        return RegisteredFluid(spec, fluid, cauldron, interactions)
    }

    /** 注册 `<name>` 流体本体（FluidType + source + flowing + 液体方块 + 桶） */
    private fun registerFluid(spec: FluidSpec): FluidEntry<BaseFlowingFluid.Flowing> {
        // 贴图按 spec.texture 取：同族流体共用同一套灰度图，只靠 spec.tint 区分颜色
        val stillTexture: ResourceLocation = AnvilCraftFluid.of("block/${spec.texture}_still")
        val flowingTexture: ResourceLocation = AnvilCraftFluid.of("block/${spec.texture}_flow")

        val typeFactory = FluidBuilder.FluidTypeFactory { properties, still, flowing ->
            tintedFluidType(properties, still, flowing, spec.tint)
        }

        var builder = REGISTRUM
            .fluid(spec.name, stillTexture, flowingTexture, typeFactory)
            // 显式给出源流体：FluidBuilder.bucket() 要求 source 已经存在
            // （create() 里的 defaultSource 要等到 register() 才真正建源流体）
            .source { properties -> BaseFlowingFluid.Source(properties) }
            .properties { p ->
                p.canSwim(false)
                    .canDrown(false)
                    .pathType(PathType.LAVA)
                    .lightLevel(spec.lightLevel)
                    .density(spec.density)
                    .viscosity(spec.viscosity)
                    .temperature(spec.temperature)
                    .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL_LAVA)
                    .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY_LAVA)
            }
            .fluidProperties { p ->
                p.tickRate(spec.tickRate).explosionResistance(100.0f)
            }

        // 打标签（Registrum 会同时给源流体与流动流体打上）
        val tags = tagsFor(spec.family)
        if (tags.isNotEmpty()) builder = builder.tag(*tags.toTypedArray())

        if (!spec.placeable) {
            builder = builder.noBlock()
        } else if (WaterReactions.reactionFor(spec.name) != null) {
            // 在水反应表里的流体用自定义液体方块：碰水凝固，且区分源/流动（见 ReactiveLiquidBlock）。
            // ⚠️ 反应对象按 **spec.name** 查好再传进去——不要让它拿液体方块里的 fluid 反推名字，
            //    那拿到的是 `flowing_<name>`，会让所有反应失效。
            // ⚠️ 与 bucket 同理：自己调用 block() 后 defaultBlock 变 false，
            //    FluidBuilder.register() 不再自动注册它，必须自己 .register()
            val reaction = WaterReactions.reactionFor(spec.name)!!
            builder.block { fluid, properties ->
                ReactiveLiquidBlock(fluid, properties, reaction)
            }.register()
        } else if (isReforging(spec)) {
            // 余烬液体：接触即重铸修复（对齐上游火/岩浆的修复方式），见 ReforgingFluidBlock。
            // 同样必须自己 .register()（理由同上）。
            builder.block { fluid, properties ->
                ReforgingFluidBlock(fluid, properties)
            }.register()
        }

        // 桶用**双层模型**：layer0 灰铁桶身（不染色）+ layer1 桶内液体（染 spec.tint）。
        // 共用一个手写模板 anvilcraft_fluid:item/bucket_template，
        // 每种流体的模型**由 datagen 生成**而不是手写 JSON——
        // 手写的话新增流体时很容易漏文件（曾经就漏了 4 个，表现为"物品贴图缺少"）。
        // ⚠️ 自己调用 bucket() 之后 `defaultBucket` 会变成 false，
        //    FluidBuilder.register() 就不再自动注册它了，必须自己 `.register()`，
        //    否则 Registrum 会报 "Found unused register callbacks"。
        builder.bucket()
            .model { ctx, provider ->
                provider.withExistingParent(ctx.name, AnvilCraftFluid.of("item/bucket_template"))
            }
            .register()

        return builder.register()
    }

    /** 注册 `<name>_cauldron` 满锅方块（blockstate / 模型 / 战利品表为 src/main/resources 下手工 JSON） */
    private fun registerCauldron(
        spec: FluidSpec,
        interactions: CauldronInteraction.InteractionMap,
        reforging: Boolean,
    ): BlockEntry<AddonCauldronBlock> = REGISTRUM
        .block("${spec.name}_cauldron") { properties -> AddonCauldronBlock(properties, interactions, reforging) }
        .properties { p ->
            p.strength(2.0f)
                .noOcclusion()
                .lightLevel { _ -> spec.lightLevel }
        }
        .tag(BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.CAULDRONS)
        // 锅没有物品形式（与原版炼药锅一致：只能由桶交互产生），敲掉时掉原版炼药锅。
        // 不写这条的话 Registrum 会生成 name = minecraft:air 的空战利品表。
        .loot { tables, block -> tables.dropOther(block, Items.CAULDRON) }
        .blockstate { ctx, provider ->
            // 锅模型**由 datagen 生成**（不再手写 JSON）：父级是原版 template_cauldron_full，
            // content 面指向该族共用的灰度静止贴图，配方块颜色处理器上色。
            // 好处：新增流体时不会漏文件（曾经手写漏了 4 个桶模型）。
            provider.models().getBuilder("${spec.name}_cauldron")
                .parent(cauldronTemplate(provider))
                .texture("bottom", vanillaTexture("block/cauldron_bottom"))
                .texture("content", AnvilCraftFluid.of("block/${spec.family.textureBase}_still"))
                .texture("inside", vanillaTexture("block/cauldron_inner"))
                .texture("particle", vanillaTexture("block/cauldron_side"))
                .texture("side", vanillaTexture("block/cauldron_side"))
                .texture("top", vanillaTexture("block/cauldron_top"))

            val model = ModelFile.ExistingModelFile(
                AnvilCraftFluid.of("block/${spec.name}_cauldron"),
                provider.models().existingFileHelper,
            )
            provider.getVariantBuilder(ctx.get())
                .forAllStates { ConfiguredModel.builder().modelFile(model).build() }
        }
        .register()

    /** 原版方块模型引用 */
    private fun cauldronTemplate(provider: RegistrumBlockstateProvider): ModelFile =
        ModelFile.ExistingModelFile(
            ResourceLocation.withDefaultNamespace("block/template_cauldron_full"),
            provider.models().existingFileHelper,
        )

    /** 原版贴图引用 */
    private fun vanillaTexture(path: String): ResourceLocation =
        ResourceLocation.withDefaultNamespace(path)

    /** 流体族 → 该进的标签列表：熔融族进 `#molten` + 族标签；功能流体只进 `#special` */
    private fun tagsFor(family: FluidFamily): List<TagKey<Fluid>> = buildList {
        if (family.molten) add(AddonFluidTags.MOLTEN)
        family.familyTagPath?.let { add(AddonFluidTags.of(it)) }
    }

    /**
     * 是否是**接触即重铸修复**的流体（目前只有余烬液体）。
     *
     * 用户口径："余烬液体的修复和原本在熔岩中的修复一样，只是快一些，和炼药锅无关。"
     * 所以它既不做成大型炼药锅的行为，也不消耗自身，只是让站在里面的掉落物被修
     * （[ReforgingFluidBlock] / [AddonCauldronBlock]）。
     */
    private fun isReforging(spec: FluidSpec): Boolean = spec.name == AddonFluidSpecs.EMBER_FLUID.name

    /**
     * 灰度贴图 + [tint] 染色的 [FluidType]。
     *
     * 世界流体的颜色是贴图与 [IClientFluidTypeExtensions.getTintColor] 相乘的结果，
     * 因此贴图为灰度图时，改 [tint] 就等于改整个流体的颜色。
     */
    private fun tintedFluidType(
        properties: FluidType.Properties,
        still: ResourceLocation,
        flowing: ResourceLocation,
        tint: Int,
    ): FluidType = object : FluidType(properties) {
        @Suppress("OVERRIDE_DEPRECATION", "removal")
        override fun initializeClient(consumer: Consumer<IClientFluidTypeExtensions>) {
            consumer.accept(object : IClientFluidTypeExtensions {
                override fun getStillTexture(): ResourceLocation = still

                override fun getFlowingTexture(): ResourceLocation = flowing

                override fun getTintColor(): Int = tint
            })
        }
    }
}
