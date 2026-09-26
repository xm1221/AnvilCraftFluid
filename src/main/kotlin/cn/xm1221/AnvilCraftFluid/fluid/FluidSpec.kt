package cn.xm1221.AnvilCraftFluid.fluid

import cn.xm1221.AnvilCraftFluid.init.AddonBlockTags
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * 流体族。族决定**共用哪一套灰度贴图**，以及进哪些流体标签。
 *
 * @property textureBase 贴图基名（`textures/block/<textureBase>_still.png` / `_flow.png`）
 * @property molten 是否算"熔融材料"，即进总标签 `#anvilcraft_fluid:molten`
 * @property familyTagPath 族标签路径；null 表示只进总标签
 */
enum class FluidFamily(
    val textureBase: String,
    val molten: Boolean,
    val familyTagPath: String?,
) {
    /** 熔融宝石族 */
    GEM("molten_gem", true, "molten_gem"),

    /** 熔融金属族 */
    METAL("molten_metal", true, "molten_metal"),

    /**
     * 功能流体族（浮霜 / 余烬 / 诅咒金）。
     *
     * 它们不是"熔融材料"而是"有特殊作用的流体"，所以**不进** `#molten`，只进 `#special`。
     * 贴图暂时复用金属族（都是液态质感）；想给它们专属美术时，
     * 补一对 `frost_fluid_still/flow.png` 并在对应的 [FluidSpec] 上写 `texture = "frost_fluid"` 即可。
     */
    SPECIAL("molten_metal", false, "special"),
}

/**
 * 一种熔融流体的定义。
 *
 * ## 贴图约定（灰度图 + 内部上色）
 *
 * 贴图与注册名**解耦**：[family] 决定用哪套图，[tint] 决定颜色。
 * 同一族的流体现在共用一张灰度图，只靠 [tint] 区分——改色不动贴图。
 *
 * | 载体 | 贴图 | 上色入口 |
 * | --- | --- | --- |
 * | 世界流体（静止） | `textures/block/<texture>_still.png` | `IClientFluidTypeExtensions#getTintColor` |
 * | 世界流体（流动） | `textures/block/<texture>_flow.png` | 同上 |
 * | 炼药锅内容面 | 复用 `<texture>_still` | 方块颜色处理器（模型 content 面 `tintindex: 0`） |
 * | 桶 | `textures/item/bucket.png`（桶身，不染色）+ `textures/item/bucket_fluid.png`（液体，染 layer1） | 物品颜色处理器 |
 *
 * 因此贴图里 **最亮处应接近纯白**（表示该像素就是纯 [tint] 色），越暗越深；
 * 不要用纯黑（相乘后仍是黑，会变成死斑）。
 *
 * @property name 注册名（决定流体 / 桶 / 炼药锅的 id）
 * @property tint ARGB 染色，`0xFFFFFFFF` 表示不染色
 * @property family 流体族（决定共用贴图与族标签）
 * @property texture 贴图基名，默认取 [family] 的；想让某种流体单独用一套图就覆盖它并补文件
 * @property lightLevel 流体发光等级（0~15）
 * @property density 密度（越大越"重"，水=1000，岩浆=3000）
 * @property viscosity 粘稠度（越大流得越慢，水=1000，岩浆=6000）
 * @property temperature 温度（岩浆=1300，用于冰冻/蒸发判定）
 * @property tickRate 流动更新间隔（tick），越小流得越快
 * @property levelDecreasePerBlock 每流一格液面下降多少：**越大铺得越近**（水 = 1，岩浆 = 2）
 * @property slopeFindDistance 往下游找落差的距离：**越大越容易顺着坑流下去**（水 = 4，岩浆 = 2）
 * @property contact 泡在里头会受到的影响（着火 / 伤害 / 冻结 / 状态效果 / 烧毁物品 / 重铸修复）；
 *   默认 [FluidContact.NONE] 表示泡进去什么也不会发生
 * @property placeable false 时只有桶 + 炼药锅，不注册可放置的世界流体方块
 *
 * ## 流速的三个旋钮
 *
 * 三者各管一件事，想调"流得多快 / 流得多远"就动它们（都直接对应
 * `BaseFlowingFluid.Properties` 的同名方法）：
 *
 * | 想要的效果 | 改哪个 | 往哪改 |
 * | --- | --- | --- |
 * | 扩散得**更慢**（像岩浆那样一格一格挪） | [tickRate] | 调大（水 = 5，我们默认 10） |
 * | 流得**更近**（摊成一小滩就停） | [levelDecreasePerBlock] | 调大 |
 * | 更容易**顺着落差往下**流 | [slopeFindDistance] | 调大 |
 *
 * ## 影响
 *
 * [contact] 是声明式的：想给某种流体加影响只在它的 [FluidSpec] 里写一行，
 * 注册代码不用动。字段含义见 [FluidContact]。
 */
data class FluidSpec(
    val name: String,
    val tint: Int,
    val family: FluidFamily,
    val texture: String = family.textureBase,
    val lightLevel: Int = 12,
    val density: Int = 3000,
    val viscosity: Int = 6000,
    val temperature: Int = 1300,
    val tickRate: Int = 10,
    val levelDecreasePerBlock: Int = 1,
    val slopeFindDistance: Int = 4,
    val contact: FluidContact = FluidContact.NONE,
    val placeable: Boolean = true,
    /**
     * true 时给这种流体的**液面外缘**描一圈发光边框（贴图见 [outlineTexture]）。
     *
     * ⚠️ 这里**不再**给源方块挂整块模型了（用户口径：源方块不一定需要边框，边框只在流体边缘）。
     * 世界里的液体方块一律保持原版渲染，边框只在流体真正露在外面的边缘由客户端现画
     * （`client/FluidOutlineRenderer`），所以连成一片的流体只有整片的外轮廓发亮。
     */
    val outlined: Boolean = false,
    /**
     * 液面描边用的贴图 id。为 null 就不描边。
     *
     * **颜色由贴图自己决定**：本模组直接用上游那两张预上色的
     * `anvilcraft:block/{frost,ember}_metal_block_outline`（浮霜近白、余烬黄），
     * 客户端只乘白色顶点色，所以边框看着和上游金属块那一圈一模一样。
     *
     * 描边由客户端在 `LiquidBlockRenderer#tesselate` 末尾、
     * 用原版算出的（含邻居平均的）角点高度现画，源/流动各档、斜面都贴得严丝合缝。
     */
    val outlineTexture: String? = null,
    /**
     * **桶描边**（`models/item/<name>_bucket.json` 的 `layer2`）的颜色，`0xAARRGGBB`。
     *
     * 与 [outlined] / [outlineTexture] **互相独立、单独指定**：为 null 时桶不加描边层，
     * 有值才加。口径是"**和这种流体的液面边框颜色一致**"——
     * 两边不是同一个数据源（边框色来自贴图，这里是一个常量），改贴图时要跟着改这里。
     */
    val bucketOutlineTint: Int? = null,
    /**
     * **桶内液体那一层**（`models/item/<name>_bucket.json` 的 `layer1`）的颜色，`0xAARRGGBB`。
     *
     * 那一层贴图 `item/bucket_fluid` 是**所有流体公用的灰度图**，所以它必须要一个颜色来乘。
     * 为 null（默认）时用 [tint]；世界液面贴图**自带成品色**、不想被任何颜色覆盖的流体
     * （如红石树脂胶体），就把 [tint] 写成白色 `0xFFFFFFFF`，颜色单独写在这里。
     */
    val bucketFluidTint: Int? = null,
    /**
     * **按状态切换的液面描边颜色**：未激活 / 已激活（都是 `0xAARRGGBB`）。
     *
     * 只有"会被点亮的"流体用得上（现在是红石树脂胶体）：它的 [outlineTexture]
     * 是一张**纯白**贴图（`anvilcraft_fluid:block/fluid_outline`），
     * 白色贴图乘上这两个颜色，就得到暗红 / 亮红两态。
     * 浮霜、余烬这两个字段都留 null，颜色照旧由上游那张预上色贴图自带。
     *
     * ⚠️ 声明了 [outlineTintOn] 的流体，描边的**自发光也跟着状态走**：
     * 未激活用该格的正常光照（放在暗处就是暗的），只有激活才全亮——
     * 这样"激活才亮、不激活为暗"在夜里也读得出来。
     */
    val outlineTintOff: Int? = null,
    /** 已激活时的液面描边颜色，语义见 [outlineTintOff] */
    val outlineTintOn: Int? = null,
    /**
     * **不会被这种流体冲掉的方块清单**（"某些流体不冲掉某些方块"）。
     *
     * 流体蔓延时，原版 `FlowingFluid#canSpreadTo` 会先问 `FluidState#canBeReplacedWith`：
     * 返回 false 时流体**根本不会流进那一格**，方块既不会被替换、也不会掉物品
     * （掉落发生在更后面的 `beforeDestroyingBlock` 里）。清单就是在这个闸门上拦的，
     * 见 `mixin/FlowingFluidMixin`。
     *
     * 三个入口，彼此是"或"关系：[washResistant] 直接写方块、[washResistantTags] 用原版/上游现成标签、
     * [washResistantIds] 用注册名（跨模组又不想加编译依赖时）。例：
     * `washResistantTags = setOf(BlockTags.RAILS)`、
     * `washResistantIds = setOf(ResourceLocation.fromNamespaceAndPath("anvilcraft", "redstone_wire"))`
     *
     * ⚠️ **别把流体方块写进来**：流体冲掉流体是"流体对流体"反应
     * （浮霜源 + 余烬源 → 黑石 那类）的入口；那条路已经由 mixin 主动放过。
     * ⚠️ 拦下来 = 流体流不进那一格，会从旁边绕开／积在周围——通常正是想要的效果。
     */
    val washResistant: Set<Block> = emptySet(),
    /**
     * **不会被这种流体冲掉的方块标签**（[washResistant] 的标签版，两者是"**或**"关系）。
     *
     * 用标签的好处：① 原版/上游已经分好组的直接复用（`minecraft:buttons`、`minecraft:pressure_plates`、
     * `minecraft:rails`、`anvilcraft:sliding_rails` 等）；② 数据包能自己往里加东西。
     *
     * ⚠️ 标签**只能在运行时判定**（`BlockState#is(TagKey)`）：标签是数据包加载出来的，
     * 注册期去解析只会拿到空集。所以 mixin 里是拿标签现查，不是提前展开成方块表。
     */
    val washResistantTags: Set<TagKey<Block>> = emptySet(),
    /**
     * **按注册名指定的"冲不掉"清单**（与 [washResistant]、[washResistantTags] 都是"或"关系）。
     *
     * 存在的理由：上游模组的方块可能没有现成标签，而我们又不想为了引用它而在编译期依赖它
     * （如 `anvilcraft:redstone_wire`）。注册名在运行时查，找不到也不报错。
     */
    val washResistantIds: Set<ResourceLocation> = emptySet(),
    /**
     * true 时这种流体的世界液体方块是**红石导体**
     * （方块类见 `block/RedstoneResinBlock`）：被某一侧红石激活后，向**除那一侧以外**的
     * 五个方向充能 15 级；而那一侧的信号一旦消失，它自己也就熄掉（见该类注释里的方向口径）。
     *
     * 源方块与流动方块本来就是同一个方块类的不同档位，所以"流动的、源方块"两者都算数。
     */
    val conductsRedstone: Boolean = false,
)

/**
 * 共用的桶贴图基名（桶是全模组共用的，与流体族无关）。
 *
 * | 文件 | 尺寸 | 染色 |
 * | --- | --- | --- |
 * | `textures/item/bucket.png` | 16×16 | ❌ 灰铁桶身，按普通铁桶画 |
 * | `textures/item/bucket_fluid.png` | 16×16 | ✅ 灰度，画在桶身内壁上 |
 *
 * 流体贴图基名见 [FluidFamily.textureBase]。
 */
object AddonFluidTextures {
    /** 桶身（灰铁，不染色） */
    const val BUCKET = "bucket"

    /** 桶内液体（灰度，染色） */
    const val BUCKET_FLUID = "bucket_fluid"
}

/**
 * 本模组的全部熔融流体定义表。
 *
 * **颜色只在这里改**：调整 [FluidSpec.tint] 不需要重画任何贴图。
 * 同族流体共用 [FluidFamily] 的贴图基名。
 */
object AddonFluidSpecs {

    /**
     * `0xAARRGGBB` 字面量转 Int（Kotlin 的十六进制字面量超过 Int 范围时是 Long）。
     *
     * ⚠️ 本项目的颜色一律写成 **`0xAARRGGBB` 十六进制**，alpha 必须是 `FF`。
     * 写成十进制（例如 `383030`）时它实际是 `0x0005D836`——**alpha = 0**，
     * 于是流体和桶内液体都变成全透明（看上去"像没有贴图"）。
     * 要改色只改 [FluidSpec.tint]，不需要动任何贴图。
     */
    private fun argb(value: Long): Int = value.toInt()

    // ───────────────────────── 熔融宝石（共用 GEM 贴图） ─────────────────────────

    /** 熔融红宝石 */
    val MOLTEN_RUBY = FluidSpec("molten_ruby", argb(0xFFE23A4E), FluidFamily.GEM)

    /** 熔融石英 */
    val MOLTEN_QUARTZ = FluidSpec("molten_quartz", argb(0xFFF3EAD8), FluidFamily.GEM)

    /** 熔融蓝宝石 */
    val MOLTEN_SAPPHIRE = FluidSpec("molten_sapphire", argb(0xFF3A6FE0), FluidFamily.GEM)

    /** 熔融黄玉（AnvilCraft 官方译名，非"黄宝石"） */
    val MOLTEN_TOPAZ = FluidSpec("molten_topaz", argb(0xFFF0B03A), FluidFamily.GEM)

    /** 熔融绿宝石 */
    val MOLTEN_EMERALD = FluidSpec("molten_emerald", argb(0xFF2CC46A), FluidFamily.GEM)

    /** 熔融紫水晶（对应原版紫水晶碎片；遇水 → 方解石） */
    val MOLTEN_AMETHYST = FluidSpec("molten_amethyst", argb(0xFF9A5FD8), FluidFamily.GEM)

    val GEMS: List<FluidSpec> = listOf(
        MOLTEN_RUBY,
        MOLTEN_QUARTZ,
        MOLTEN_SAPPHIRE,
        MOLTEN_TOPAZ,
        MOLTEN_EMERALD,
        MOLTEN_AMETHYST,
    )

    // ───────────────────────── 熔融金属（共用 METAL 贴图） ─────────────────────────

    /** 熔融铁 */
    val MOLTEN_IRON = FluidSpec(
        "molten_iron", argb(0xFFE6E1DA), FluidFamily.METAL, lightLevel = 10,
    )

    /** 熔融金 */
    val MOLTEN_GOLD = FluidSpec(
        "molten_gold", argb(0xFFFFC93A), FluidFamily.METAL, lightLevel = 12,
    )

    /** 熔融铜 */
    val MOLTEN_COPPER = FluidSpec(
        "molten_copper", argb(0xFFE07A3F), FluidFamily.METAL, lightLevel = 11,
    )

    /**
     * 熔融钨。
     *
     * 颜色由用户给出：`#2a2422` → `0xFF2A2422`（深褐灰）。
     * 温度 3400 是全表最高（钨是熔点最高的金属，现实约 3422°C）。
     */
    val MOLTEN_TUNGSTEN = FluidSpec(
        "molten_tungsten", argb(0xFF2A2422), FluidFamily.METAL, temperature = 3400, lightLevel = 14,
    )

    /** 熔融皇家钢：用户给的 `#4c5459` → `0xFF4C5459`（青灰蓝） */
    val MOLTEN_ROYAL_STEEL = FluidSpec(
        "molten_royal_steel", argb(0xFF4C5459), FluidFamily.METAL, temperature = 500, lightLevel = 13,
    )

    // ───────── AnvilCraft 的其余金属（用户要求"有矿石的金属"都要注册） ─────────
    // 这 6 种在 AnvilCraft 里**只有深层矿石**（deepslate_<metal>_ore），没有普通矿石版本。

    /** 熔融铅（铅矿 → `anvilcraft:deepslate_lead_ore`） */
    val MOLTEN_LEAD = FluidSpec("molten_lead", argb(0xFF5A5F6E), FluidFamily.METAL, temperature = 350)

    /** 熔融银（银矿 → `anvilcraft:deepslate_silver_ore`） */
    val MOLTEN_SILVER = FluidSpec("molten_silver", argb(0xFFDDE3EA), FluidFamily.METAL, temperature = 1000)

    /** 熔融锡（锡矿 → `anvilcraft:deepslate_tin_ore`） */
    val MOLTEN_TIN = FluidSpec("molten_tin", argb(0xFFB9C0C7), FluidFamily.METAL, temperature = 250, lightLevel = 9)

    /** 熔融锌（锌矿 → `anvilcraft:deepslate_zinc_ore`） */
    val MOLTEN_ZINC = FluidSpec("molten_zinc", argb(0xFF9FB0C0), FluidFamily.METAL, temperature = 450)

    /** 熔融钛（钛矿 → `anvilcraft:deepslate_titanium_ore`） */
    val MOLTEN_TITANIUM = FluidSpec("molten_titanium", argb(0xFF6E6A8A), FluidFamily.METAL, temperature = 1700)

    /**
     * 熔融铀（铀矿 → `anvilcraft:deepslate_uranium_ore`）。
     *
     * 泡在里面会**凋零**（用户口径）。伤害交给凋零效果自己结算，
     * 所以不需要自定义伤害类型（见 [AddonDamageTypes]）。
     */
    val MOLTEN_URANIUM = FluidSpec(
        "molten_uranium", argb(0xFFB7D24A), FluidFamily.METAL, temperature = 1150, lightLevel = 14,
        contact = FluidContact(
            effects = listOf(ContactEffect(ResourceLocation.withDefaultNamespace("wither"), 60)),
        ),
    )

    val METALS: List<FluidSpec> = listOf(
        MOLTEN_IRON,
        MOLTEN_GOLD,
        MOLTEN_COPPER,
        MOLTEN_TUNGSTEN,
        MOLTEN_ROYAL_STEEL,
        MOLTEN_LEAD,
        MOLTEN_SILVER,
        MOLTEN_TIN,
        MOLTEN_ZINC,
        MOLTEN_TITANIUM,
        MOLTEN_URANIUM,
    )

    // ───────────────────── 功能流体（共用 SPECIAL 贴图） ─────────────────────

    /**
     * 浮霜流体：洗去物品附魔（见 `event/CauldronItemReactions.kt`）。
     *
     * 泡在里面**就像待在细雪里**：冻结值一点点累积，冻满了开始掉血，视野也会结霜。
     * 实现上只把原版那个"在细雪里"的标记点起来，其余全交给原版
     * （见 [FluidContactApplier]），所以手感与细雪一致。
     */
    val FROST_FLUID = FluidSpec(
        "frost_fluid", argb(0xFFBFE6F5), FluidFamily.SPECIAL,
        lightLevel = 6, temperature = 300, viscosity = 2000,
        contact = FluidContact(freezing = true),
        outlined = true,
        outlineTexture = "anvilcraft:block/frost_metal_block_outline",
        // 桶描边颜色：单独指定，取值对应上面那圈边框——量自 frost_metal_block_outline（近白）
        bucketOutlineTint = argb(0xFFDAE1E7),
    )

    /**
     * 余烬流体：加速余烬装备的重铸修复。
     *
     * 泡在里面会**着火**并持续受到大量伤害，**普通物品会被烧毁**（像岩浆）。
     * 但火焰免疫的物品（余烬金属装备等）既不受伤也不会被烧毁，其中带重铸组件的还会被修好——
     * 这正是上游让重铸装备在岩浆里活下来的同一套机制，见 [FluidContact] 的类注释。
     *
     * 贴图是**自己的**一对（占位图已就位，直接覆盖那两张 png 就行）：
     * `block/ember_fluid_still.png`（16×128 = 8 帧 16×16）与
     * `block/ember_fluid_flow.png`（32×256 = 8 帧 32×32），各配一个 `*.png.mcmeta`。
     * 因为是**成品色**贴图（用户自己画），[tint] 留白（白色 = 不染色），
     * 世界液面与锅内液面都原样显示画好的样子；桶内液体那一层的颜色见 [bucketFluidTint]。
     */
    val EMBER_FLUID = FluidSpec(
        // 白色 = 不染色：贴图是用户画的成品色
        "ember_fluid", argb(0xFFFFFFFF), FluidFamily.SPECIAL,
        // 桶内液体那一层是公用灰度图，沿用原来的颜色（与熔融钨一致，用户口径）
        bucketFluidTint = argb(0xFF2A2422),
        texture = "ember_fluid",
        lightLevel = 15, temperature = 2000, viscosity = 4000,
        contact = FluidContact(
            igniteSeconds = 15,
            damage = 6f,
            damageInterval = 10,
            damageType = AddonDamageTypes.EMBER,
            reforgePerTick = 1,
        ),
        outlined = true,
        outlineTexture = "anvilcraft:block/ember_metal_block_outline",
        // 桶描边颜色：单独指定，取值对应上面那圈边框——量自 ember_metal_block_outline（黄）
        bucketOutlineTint = argb(0xFFEAB302),
    )

    /**
     * 红石树脂胶体：红石粉与树脂调成的胶体，**能传导红石信号**。
     *
     * 被某一侧的红石激活时，它向**除那一侧以外**的五个方向充能 15 级
     * （红石元件与可充能方块都吃得到），也会把挨着的同类胶体一起点亮，
     * 于是一整片胶体连成一条电路；**那一侧的信号一消失它就熄掉**，
     * 而且不会回灌给供电的那一侧（所以相邻两格不会互相供电锁死）。
     * 实现见 `block/RedstoneResinBlock`。
     *
     * 边框随状态变色：激活是亮红，未激活是暗红而且**不发光**（见 [outlineTintOff] / [outlineTintOn]）。
     *
     * 贴图是**自己的**一对（占位图已就位，直接覆盖那两张 png 就行）：
     * `block/redstone_resin_still.png`（16×128 = 8 帧 16×16）与
     * `block/redstone_resin_flow.png`（32×256 = 8 帧 32×32），各配一个 `*.png.mcmeta`。
     *
     * ⚠️ 这两张是**成品色**贴图（用户自己画），所以 [tint] 留**白色**：
     * 白色 = 不染色，世界液面与锅内液面都原样显示画好的样子，不被任何颜色覆盖。
     * 桶内液体那一层是公用灰度图，颜色另见 [bucketFluidTint]。
     */
    val REDSTONE_RESIN = FluidSpec(
        "redstone_resin", argb(0xFFFFFFFF), FluidFamily.SPECIAL,
        // 桶内液体那一层（公用灰度图 item/bucket_fluid，用户指定 #d9ad56）
        bucketFluidTint = argb(0xFFD9AD56),
        texture = "redstone_resin",
        lightLevel = 0, temperature = 300, viscosity = 3000,
        // ⚠️ 红石树脂**不冲掉红石元件**（MC + AnvilCraft）。
        // 清单是 datagen 生成的标签：`anvilcraft_fluid:wash_proof/redstone`，
        // 内容见 `data/block/AddonBlockTagHandler`（改那里，不要手写 json）。
        // 别的流体想要自己的清单，就照抄一个 `wash_proof/xxx` 标签再在这里引用。
        washResistantTags = setOf(AddonBlockTags.WASH_PROOF_REDSTONE),
        conductsRedstone = true,
        outlined = true,
        // 边框贴图是**纯白**的 anvilcraft_fluid:block/fluid_outline，颜色全靠下面两个 tint
        outlineTexture = "anvilcraft_fluid:block/fluid_outline",
        outlineTintOff = argb(0xFF6E1216),
        outlineTintOn = argb(0xFFFF3B30),
        // 桶没法通电，取"激活"那一档的亮红，物品栏里看得清楚
        bucketOutlineTint = argb(0xFFFF3B30),
    )

    /**
     * 诅咒金流体：洗掉诅咒附魔后的熔融金（上游诅咒金体系）。
     *
     * 泡在里面会持续**虚弱**（每 tick 用 3 秒时长刷新，离开就消退）。
     */
    val CURSED_GOLD_FLUID = FluidSpec(
        "cursed_gold_fluid", argb(0xFF7A5230), FluidFamily.SPECIAL,
        lightLevel = 8, temperature = 1400,
        contact = FluidContact(
            effects = listOf(ContactEffect(ResourceLocation.withDefaultNamespace("weakness"), 60)),
        ),
    )

    val SPECIALS: List<FluidSpec> = listOf(
        FROST_FLUID,
        EMBER_FLUID,
        CURSED_GOLD_FLUID,
        REDSTONE_RESIN,
    )

    /** 本期注册的全部流体 */
    val ALL: List<FluidSpec> = GEMS + METALS + SPECIALS
}
