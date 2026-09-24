package cn.xm1221.AnvilCraftFluid.fluid

import dev.dubhe.anvilcraft.init.block.ModBlocks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * 一次遇水凝固的产物。
 *
 * **源方块与流动流体的结果可以不同**（用户要求）：
 * - 宝石族：两者一致（保留原设计）；
 * - 金属族：**源方块遇水 → 对应矿石**（一桶熔融铁浇进水里就是一块铁矿脉的雏形）；
 *   而**流动的熔融金属遇水不反应**（用户拍板：不需要"流动金属 → 石头"这一档，
 *   所以金属族的 `flowingProduct` 一律是 null——见下面的表）。
 *
 * 产物用**工厂函数**而不是方块本身：`ModBlocks.XXX.get()` 这类上游条目在
 * mod 构造阶段（注册表未填充）取值会抛 `unbound value`，而本表是 object 的静态字段、
 * 构造期就会被读到。包成 lambda 后，解析推迟到真正发生反应的运行时。
 *
 * @property sourceProduct 源方块遇水生成什么
 * @property flowingProduct 流动流体遇水生成什么；null 表示流动流体不反应
 */
data class WaterReaction(
    val sourceProduct: () -> Block,
    val flowingProduct: (() -> Block)?,
)

/**
 * 流体遇水凝固反应表。
 *
 * 实现见 [cn.xm1221.AnvilCraftFluid.block.ReactiveLiquidBlock]：
 * 表里的流体注册时会用那个自定义液体方块，碰水就把自己那格换成对应产物。
 *
 * 语义对齐上游 AnvilCraft 与原版岩浆 + 水：**反应后水保留**，只凝固自己这一格。
 *
 * 键是 [FluidSpec.name]；不在表里的流体（3 个功能流体、以及没有对应矿石的熔融皇家钢）
 * 遇水不发生任何事。
 */
object WaterReactions {

    /**
     * 流体名 → 反应产物。
     *
     * | 熔融流体 | 源方块 + 水 | 流动 + 水 |
     * | --- | --- | --- |
     * | 红宝石 | 花岗岩 | 花岗岩 |
     * | 石英 | 闪长岩 | 闪长岩 |
     * | 蓝宝石 / 黄玉 / 绿宝石 | 安山岩 | 安山岩 |
     * | 紫水晶 | 方解石 | 方解石 |
     * | 铁 | **铁矿** | 不反应 |
     * | 金 | **金矿** | 不反应 |
     * | 铜 | **铜矿** | 不反应 |
     * | 钨 | **深层钨矿石**（上游只有这一种钨矿） | 不反应 |
     */
    val TABLE: Map<String, WaterReaction> = mapOf(
        // ── 熔融宝石：源与流动一致（保留现状）──
        "molten_ruby" to WaterReaction({ Blocks.GRANITE }, { Blocks.GRANITE }),
        "molten_quartz" to WaterReaction({ Blocks.DIORITE }, { Blocks.DIORITE }),
        "molten_sapphire" to WaterReaction({ Blocks.ANDESITE }, { Blocks.ANDESITE }),
        "molten_topaz" to WaterReaction({ Blocks.ANDESITE }, { Blocks.ANDESITE }),
        "molten_emerald" to WaterReaction({ Blocks.ANDESITE }, { Blocks.ANDESITE }),
        "molten_amethyst" to WaterReaction({ Blocks.CALCITE }, { Blocks.CALCITE }),

        // ── 熔融金属：只有**源方块**遇水成矿；流动的熔融金属不反应（用户拍板）──
        "molten_iron" to WaterReaction({ Blocks.IRON_ORE }, null),
        "molten_gold" to WaterReaction({ Blocks.GOLD_ORE }, null),
        "molten_copper" to WaterReaction({ Blocks.COPPER_ORE }, null),
        "molten_tungsten" to WaterReaction({ ModBlocks.DEEPSLATE_TUNGSTEN_ORE.get() }, null),
    )

    /** 该流体是否会遇水凝固（纯查表，mod 构造阶段可安全调用） */
    fun isReactive(fluidName: String): Boolean = TABLE.containsKey(fluidName)

    /**
     * 取该流体的反应（含产物工厂，构造阶段可安全持有——工厂要到真正反应时才被调用）。
     *
     * ⚠️ **必须用 [FluidSpec.name] 来查**，不要拿液体方块里的 `fluid` 反推名字：
     * `Registrum` 建液体方块时传进去的是**流动流体**（`flowing_<name>`），
     * 用它查表会全部落空（曾经因此让所有水反应一起失效）。
     */
    fun reactionFor(fluidName: String): WaterReaction? = TABLE[fluidName]

    /** 源方块遇水的产物；null 表示不反应 */
    fun sourceProduct(fluidName: String): Block? = TABLE[fluidName]?.sourceProduct?.invoke()

    /** 流动流体遇水的产物；null 表示不反应 */
    fun flowingProduct(fluidName: String): Block? = TABLE[fluidName]?.flowingProduct?.invoke()
}
