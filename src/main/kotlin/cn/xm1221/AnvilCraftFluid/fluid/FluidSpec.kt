package cn.xm1221.AnvilCraftFluid.fluid

/**
 * 流体族。族决定了**共用哪一套灰度贴图**，以及进哪个族流体标签。
 *
 * @property textureBase 贴图基名（`textures/block/<textureBase>_still.png` / `_flow.png`）
 */
enum class FluidFamily(val textureBase: String) {
    /** 熔融宝石族 */
    GEM("molten_gem"),

    /** 熔融金属族 */
    METAL("molten_metal"),
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
 * @property placeable false 时只有桶 + 炼药锅，不注册可放置的世界流体方块
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

    /** `0xAARRGGBB` 字面量转 Int（Kotlin 的十六进制字面量超过 Int 范围时是 Long） */
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

    /** 熔融钨（高温金属，颜色偏冷灰） */
    val MOLTEN_TUNGSTEN = FluidSpec(
        "molten_tungsten", argb(0xFF9AA6B2), FluidFamily.METAL, temperature = 3400, lightLevel = 14,
    )

    /** 熔融皇家钢（宝石魔力浸染的铁） */
    val MOLTEN_ROYAL_STEEL = FluidSpec(
        "molten_royal_steel", argb(0xFFD0A6E8), FluidFamily.METAL, temperature = 1600, lightLevel = 13,
    )

    val METALS: List<FluidSpec> = listOf(
        MOLTEN_IRON,
        MOLTEN_GOLD,
        MOLTEN_COPPER,
        MOLTEN_TUNGSTEN,
        MOLTEN_ROYAL_STEEL,
    )

    /** 本期注册的全部流体（P0-2 第一批） */
    val ALL: List<FluidSpec> = GEMS + METALS
}
