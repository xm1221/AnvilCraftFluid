package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.recipe.AddonRecipeTypes
import cn.xm1221.AnvilCraftFluid.recipe.MultiFluidMixingRecipe
import dev.anvilcraft.resource.ageratum.client.feat.markdown.component.extend.MDRecipeComponent
import dev.anvilcraft.lib.v2.util.predicate.ChanceItemStack
import dev.anvilcraft.lib.v2.util.predicate.ItemIngredientPredicate
import dev.anvilcraft.resource.ageratum.client.feat.markdown.MDRenderContext
import dev.anvilcraft.resource.ageratum.client.registries.AgeratumRegistries
import dev.dubhe.anvilcraft.block.LargeCauldronBlock
import dev.dubhe.anvilcraft.block.state.Cube3x3PartHalf
import dev.dubhe.anvilcraft.client.markdown.recipe.anvil.MDBaseAnvilRecipeComponent
import dev.dubhe.anvilcraft.init.block.ModBlocks
import dev.dubhe.anvilcraft.recipe.anvil.predicate.block.HasCauldron
import dev.dubhe.anvilcraft.util.AgeratumUtil
import dev.dubhe.anvilcraft.recipe.component.HasCauldronSimple
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * **藿香（Ageratum）手册的配方展示组件**——让本模组自研的配方类型
 * `anvilcraft_fluid:multi_fluid_mixing` 也能用 `<recipe id="…"/>` 在手册里渲染成真配方，
 * 而不是拿文字描述。
 *
 * ## 为什么需要这一个类
 *
 * 手册的配方展示是**按配方类型注册**的：AnvilCraft 在
 * `AnvilCraftRecipeComponentFactories` 里为它自己的每个配方类型注册了工厂
 * （`RecipeComponentFactory.create(配方类型, ::组件)`，注册进
 * `AgeratumRegistries.RECIPE_COMPONENT_FACTORY_REGISTRY_KEY`）。
 * 自研类型不在其中，所以要看真配方就得自己补一个工厂——就是本文件。
 *
 * ## ⚠️ 加载守卫
 *
 * 本文件引用了藿香的类，而藿香对玩家是**可选**的（我们只 compileOnly 依赖它）。
 * 因此这个 object 只能由 [AnvilCraftFluidCompat] 的守卫分支触碰：
 * `ModList.get().isLoaded("ageratum")` 通过才加载，否则玩家没装藿香时会直接崩。
 */
object AddonAgeratumGuideRecipes {

    private val FACTORIES: DeferredRegister<MDRecipeComponent.RecipeComponentFactory<*>> =
        DeferredRegister.create(
            AgeratumRegistries.RECIPE_COMPONENT_FACTORY_REGISTRY_KEY,
            "anvilcraft_fluid",
        )

    /**
     * 注册本模组类型的展示组件。
     *
     * 注册名就是手册里 `<recipe>` 解析时用的类型键，这里用与配方类型同名的
     * `multi_fluid_mixing`。
     */
    val MULTI_FLUID_MIXING: Any = FACTORIES.register(
        "multi_fluid_mixing",
        // ⚠️ DeferredRegister.register 有 (String, Supplier) 与 (String, Function) 两个重载，
        //    尾随 lambda 会歧义，必须显式包一层 Supplier（本项目里这是第二次踩）。
        java.util.function.Supplier {
            MDRecipeComponent.RecipeComponentFactory.create<MultiFluidMixingRecipe>(
                AddonRecipeTypes.MULTI_FLUID_MIXING_TYPE.get(),
                // ⚠️ 同理：create 也有两个重载，必须传显式 BiFunction 对象而不是尾随 lambda
                java.util.function.BiFunction { recipe, enableAlignCenter ->
                    MultiFluidMixingRecipeComponent(recipe, enableAlignCenter)
                },
            )
        },
    )

    /** 由 mod 构造阶段在守卫分支里调用 */
    fun register(bus: IEventBus) {
        FACTORIES.register(bus)
    }
}

/**
 * `multi_fluid_mixing` 的手册展示。
 *
 * 基类 [MDBaseAnvilRecipeComponent] 负责画炼药锅/铁砧那套机器与槽位，
 * 只要告诉它"输入物品、产出物品、输入与输出的容器方块"即可——
 * 本模组这些信息都在 [MultiFluidMixingRecipe] 里，照搬就行。
 *
 * ⚠️ **额外流体（`extra_fluids`）目前不在这张图上**：基类只认
 * `HasCauldronSimple` 那一种流体。所以手册正文里对这类配方的"另一种流体"
 * 仍保留一行说明文字，两边配合看。（要把它也画进图里，得自己重写
 * `renderRecipe`，属于后续可做的增强。）
 */
class MultiFluidMixingRecipeComponent(
    private val recipe: MultiFluidMixingRecipe,
    enableAlignCenter: Boolean,
) : MDBaseAnvilRecipeComponent(enableAlignCenter) {

    /**
     * 输入物品：配方本身的输入物品 + **额外流体的桶**。
     *
     * 藿香没有流体绘制（可用标签只有 block/item/recipe/text…，**没有 fluid**），
     * 所以手册里"一格流体"只能用别的东西代表——这里用**这个流体的桶**，
     * 玩家一眼知道要往锅里加哪种液体；数量写在图下那行字里。
     */
    override fun getIngredients(): List<ItemIngredientPredicate> = buildList {
        addAll(recipe.itemIngredients)
        recipe.extraFluids.forEach { requirement ->
            requirement.candidates().firstOrNull()?.bucket?.let { bucket ->
                add(ItemIngredientPredicate.of(bucket).build())
            }
        }
    }

    /** 产出物品：配方本身的产出 + **产出流体的桶**（同样是为了在图上看得见） */
    override fun getResultItems(): List<ChanceItemStack> = buildList {
        addAll(recipe.results)
        recipe.fluidResults.forEach { fluid ->
            fluid.fluid.bucket?.let { bucket ->
                add(ChanceItemStack.of(ItemStack(bucket)))
            }
        }
    }

    /**
     * 输入容器：**大型炼药锅**。
     *
     * 本模组的配方都在大型炼药锅里做，所以机器就是它：主流体由这口锅自己表现
     * （锅里装着什么，看模型就知道），额外流体则用上面的桶表示。
     */
    override fun getInputBlockStates(): List<BlockState> = listOf(largeCauldron())

    /** 输出容器：还是那口大锅——熔液换了名字，锅没换 */
    override fun getOutputBlockState(): BlockState = largeCauldron()

    /**
     * 在图下方写清楚"旁边那口锅是哪来的、要多少"。
     *
     * 说法照上游（`MDTimeWarpRecipeComponent`）：`消耗 X mB 的 <炼药锅名>`，
     * 这里把"消耗"换成"另需"，因为主流体已经由大锅那张图表达了。
     */
    override fun renderRecipe(context: MDRenderContext, mouseX: Float, mouseY: Float) {
        super.renderRecipe(context, mouseX, mouseY)
        val graphics = context.graphics()
        recipe.extraFluids.forEachIndexed { index, requirement ->
            val cauldronName = requirement.candidates().firstOrNull()
                ?.let { ItemStack(it.bucket).hoverName }
                ?: Component.literal(requirement.id())
            AgeratumUtil.renderText(
                graphics,
                Component.translatable(EXTRA_FLUID_KEY, requirement.amount, cauldronName),
                INFO_X,
                INFO_Y + index * 11,
            )
        }
    }

    /** 大型炼药锅只画正中一块 */
    private fun largeCauldron(): BlockState = ModBlocks.LARGE_CAULDRON.getDefaultState()
        .setValue(LargeCauldronBlock.HALF, Cube3x3PartHalf.MID_CENTER)

    /** 从主流体条件里取出具体流体（`HasCauldronSimple#fluid` 是谓词，可能是标签） */
    private fun primaryFluid() =
        recipe.cauldron.fluid().fluids()
            .map { holders -> holders.firstOrNull()?.value() }
            .orElse(null)

    private fun HasCauldronSimple.fluid() = this.fluid

    companion object {
        /** 信息文字的位置，与上游各组件一致 */
        private const val INFO_X = 12
        private const val INFO_Y = 106

        private const val EXTRA_FLUID_KEY = "gui.anvilcraft_fluid.guide.extra_fluid"
    }
}

/**
 * 藿香可选性守卫。
 *
 * 本 object 自身**不引用任何藿香的类**，只做两次判断；只有判断通过才会去触碰
 * [AddonAgeratumGuideRecipes]（那个类引用了藿香）。Kotlin 每个 object 编译成独立的
 * `.class`，所以"没装藿香就不加载"是成立的——这是让藿香保持可选依赖的关键。
 */
object AddonAgeratumCompat {

    fun registerIfPresent(bus: net.neoforged.bus.api.IEventBus) {
        // 手册是客户端的东西，服务端别掺和
        if (net.neoforged.fml.loading.FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.CLIENT) return
        // 玩家没装藿香：一个字都别提，免得 NoClassDefFoundError
        if (!net.neoforged.fml.ModList.get().isLoaded("ageratum")) return
        AddonAgeratumGuideRecipes.register(bus)
    }
}