package cn.xm1221.AnvilCraftFluid.fluid

/**
 * 一种熔融流体的定义。
 *
 * ## 贴图约定（灰度图 + 内部上色）
 *
 * 贴图与注册名**解耦**：[texture] 决定用哪套图，[tint] 决定颜色。
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
 * @property texture 贴图基名，默认与 [name] 相同；同族流体指向同一个基名即可共用贴图
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
    val texture: String = name,
    val lightLevel: Int = 12,
    val density: Int = 3000,
    val viscosity: Int = 6000,
    val temperature: Int = 1300,
    val tickRate: Int = 10,
    val placeable: Boolean = true,
)

/**
 * 共用的贴图基名。
 *
 * **同族只画一套灰度图**：5 种熔融宝石共用 [GEM]，5 种熔融金属共用 [METAL]，
 * 颜色差异全部由各自的 `tint` 相乘产生。
 *
 * 每种贴图都是竖直帧条（带动画，见 `agent/开发方案.md` 9.3）：
 *
 * | 文件 | 尺寸 |
 * | --- | --- |
 * | `textures/block/<基名>_still.png` | 16 宽 × 16×N 高 |
 * | `textures/block/<基名>_flow.png` | 32 宽 × 32×N 高 |
 * | `textures/item/bucket.png` | 16×16（灰铁桶身，**不染色**，全模组共用） |
 * | `textures/item/bucket_fluid.png` | 16×16（桶内液体，灰度，**染色**，全模组共用） |
 */
object AddonFluidTextures {
    /** 熔融宝石族共用贴图 */
    const val GEM = "molten_gem"

    /** 熔融金属族共用贴图 */
    const val METAL = "molten_metal"

    /** 桶身（灰铁，不染色） */
    const val BUCKET = "bucket"

    /** 桶内液体（灰度，染色） */
    const val BUCKET_FLUID = "bucket_fluid"
}

/**
 * 本模组的全部熔融流体定义表。
 *
 * **颜色只在这里改**：调整 [FluidSpec.tint] 不需要重画任何贴图。
 * 同族流体共用 [AddonFluidTextures] 里的贴图基名。
 */
object AddonFluidSpecs {

    /** `0xAARRGGBB` 字面量转 Int（Kotlin 的十六进制字面量超过 Int 范围时是 Long） */
    private fun argb(value: Long): Int = value.toInt()

    // ───────────────────────── 熔融宝石（共用 GEM 贴图） ─────────────────────────

    /** 熔融红宝石 */
    val MOLTEN_RUBY = FluidSpec("molten_ruby", argb(0xFFE23A4E), texture = AddonFluidTextures.GEM)

    /** 熔融石英 */
    val MOLTEN_QUARTZ = FluidSpec("molten_quartz", argb(0xFFF3EAD8), texture = AddonFluidTextures.GEM)

    /** 熔融蓝宝石 */
    val MOLTEN_SAPPHIRE = FluidSpec("molten_sapphire", argb(0xFF3A6FE0), texture = AddonFluidTextures.GEM)

    /** 熔融黄玉（AnvilCraft 官方译名，非"黄宝石"） */
    val MOLTEN_TOPAZ = FluidSpec("molten_topaz", argb(0xFFF0B03A), texture = AddonFluidTextures.GEM)

    /** 熔融绿宝石 */
    val MOLTEN_EMERALD = FluidSpec("molten_emerald", argb(0xFF2CC46A), texture = AddonFluidTextures.GEM)

    val GEMS: List<FluidSpec> = listOf(
        MOLTEN_RUBY,
        MOLTEN_QUARTZ,
        MOLTEN_SAPPHIRE,
        MOLTEN_TOPAZ,
        MOLTEN_EMERALD,
    )

    // ───────────────────────── 熔融金属（共用 METAL 贴图） ─────────────────────────

    /** 熔融铁 */
    val MOLTEN_IRON = FluidSpec(
        "molten_iron", argb(0xFFE6E1DA), texture = AddonFluidTextures.METAL, lightLevel = 10,
    )

    /** 熔融金 */
    val MOLTEN_GOLD = FluidSpec(
        "molten_gold", argb(0xFFFFC93A), texture = AddonFluidTextures.METAL, lightLevel = 12,
    )

    /** 熔融铜 */
    val MOLTEN_COPPER = FluidSpec(
        "molten_copper", argb(0xFFE07A3F), texture = AddonFluidTextures.METAL, lightLevel = 11,
    )

    /** 熔融钨（高温金属，颜色偏冷灰） */
    val MOLTEN_TUNGSTEN = FluidSpec(
        "molten_tungsten", argb(0xFF9AA6B2),
        texture = AddonFluidTextures.METAL, temperature = 3400, lightLevel = 14,
    )

    /** 熔融皇家钢（宝石魔力浸染的铁） */
    val MOLTEN_ROYAL_STEEL = FluidSpec(
        "molten_royal_steel", argb(0xFFD0A6E8),
        texture = AddonFluidTextures.METAL, temperature = 1600, lightLevel = 13,
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
