package cn.xm1221.AnvilCraftFluid.fluid

/**
 * 一种熔融流体的定义。
 *
 * ## 贴图约定（灰度图 + 内部上色）
 *
 * 所有贴图一律画 **灰度图**，颜色由 [tint] 在客户端相乘得到：
 *
 * | 载体 | 贴图 | 上色入口 |
 * | --- | --- | --- |
 * | 世界流体（静止） | `textures/block/<name>_still.png` | `IClientFluidTypeExtensions#getTintColor` |
 * | 世界流体（流动） | `textures/block/<name>_flow.png` | 同上 |
 * | 炼药锅内容面 | 复用 `<name>_still` | 方块颜色处理器（模型 content 面 `tintindex: 0`） |
 * | 桶 | `textures/item/<name>_bucket.png` | 物品颜色处理器（`item/generated` 的 layer0） |
 *
 * 因此贴图里 **最亮处应接近纯白**（表示该像素就是纯 [tint] 色），越暗越深；
 * 不要用纯黑（相乘后仍是黑，会变成死斑）。
 *
 * @property name 注册名（同时决定流体、桶、锅、贴图文件名）
 * @property tint ARGB 染色，`0xFFFFFFFF` 表示不染色
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
    val lightLevel: Int = 12,
    val density: Int = 3000,
    val viscosity: Int = 6000,
    val temperature: Int = 1300,
    val tickRate: Int = 10,
    val placeable: Boolean = true,
)

/**
 * 本模组的全部熔融流体定义表。
 *
 * **颜色只在这里改**：调整 [FluidSpec.tint] 不需要重画任何贴图。
 * 贴图命名 = `textures/block/<name>_still.png` / `_flow.png` / `textures/item/<name>_bucket.png`。
 */
object AddonFluidSpecs {

    /** `0xAARRGGBB` 字面量转 Int（Kotlin 的十六进制字面量超过 Int 范围时是 Long） */
    private fun argb(value: Long): Int = value.toInt()

    // ───────────────────────── 熔融宝石 ─────────────────────────

    /** 熔融红宝石 */
    val MOLTEN_RUBY = FluidSpec("molten_ruby", argb(0xFFE23A4E))

    /** 熔融石英 */
    val MOLTEN_QUARTZ = FluidSpec("molten_quartz", argb(0xFFF3EAD8))

    /** 熔融蓝宝石 */
    val MOLTEN_SAPPHIRE = FluidSpec("molten_sapphire", argb(0xFF3A6FE0))

    /** 熔融黄玉（AnvilCraft 官方译名，非"黄宝石"） */
    val MOLTEN_TOPAZ = FluidSpec("molten_topaz", argb(0xFFF0B03A))

    /** 熔融绿宝石 */
    val MOLTEN_EMERALD = FluidSpec("molten_emerald", argb(0xFF2CC46A))

    val GEMS: List<FluidSpec> = listOf(
        MOLTEN_RUBY,
        MOLTEN_QUARTZ,
        MOLTEN_SAPPHIRE,
        MOLTEN_TOPAZ,
        MOLTEN_EMERALD,
    )

    // ───────────────────────── 熔融金属 ─────────────────────────

    /** 熔融铁 */
    val MOLTEN_IRON = FluidSpec("molten_iron", argb(0xFFE6E1DA), lightLevel = 10)

    /** 熔融金 */
    val MOLTEN_GOLD = FluidSpec("molten_gold", argb(0xFFFFC93A), lightLevel = 12)

    /** 熔融铜 */
    val MOLTEN_COPPER = FluidSpec("molten_copper", argb(0xFFE07A3F), lightLevel = 11)

    /** 熔融钨（高温金属，颜色偏冷灰） */
    val MOLTEN_TUNGSTEN = FluidSpec(
        "molten_tungsten",
        argb(0xFF9AA6B2),
        temperature = 3400,
        lightLevel = 14,
    )

    /** 熔融皇家钢（宝石魔力浸染的铁） */
    val MOLTEN_ROYAL_STEEL = FluidSpec(
        "molten_royal_steel",
        argb(0xFFD0A6E8),
        temperature = 1600,
        lightLevel = 13,
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
