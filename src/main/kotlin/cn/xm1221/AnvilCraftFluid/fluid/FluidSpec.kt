package cn.xm1221.AnvilCraftFluid.fluid

import net.minecraft.resources.ResourceLocation

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
    )

    /**
     * 余烬流体：加速余烬装备的重铸修复。
     *
     * 泡在里面会**着火**并持续受到大量伤害，**普通物品会被烧毁**（像岩浆）。
     * 但火焰免疫的物品（余烬金属装备等）既不受伤也不会被烧毁，其中带重铸组件的还会被修好——
     * 这正是上游让重铸装备在岩浆里活下来的同一套机制，见 [FluidContact] 的类注释。
     */
    val EMBER_FLUID = FluidSpec(
        "ember_fluid", argb(0xFFFF7A2A), FluidFamily.SPECIAL,
        lightLevel = 15, temperature = 2000, viscosity = 4000,
        contact = FluidContact(
            igniteSeconds = 15,
            damage = 6f,
            damageInterval = 10,
            damageType = AddonDamageTypes.EMBER,
            reforgePerTick = 1,
        ),
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
    )

    /** 本期注册的全部流体 */
    val ALL: List<FluidSpec> = GEMS + METALS + SPECIALS
}
