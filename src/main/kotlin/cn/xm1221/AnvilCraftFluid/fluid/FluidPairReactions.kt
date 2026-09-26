package cn.xm1221.AnvilCraftFluid.fluid

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * 一条"两种流体相遇"的凝固规则。
 *
 * 判定的是**自己这一格**该怎么变：规则挂在 [FluidPairReactions.TABLE] 里、
 * 以"自己那种流体的名字"为键，命中的那一条决定自己变成什么方块（对手那格不动）。
 *
 * @property other 对手流体名（[FluidSpec.name]，不是注册 id）
 * @property selfSource true = 必须是源方块；false = 必须是流动流体；null = 不限
 * @property otherSource 同上，管的是对手那一格
 * @property product 产物工厂。用工厂而不是方块本身：上游方块条目在 mod 构造阶段
 *   取值会抛 `unbound value`，解析推迟到真正反应时（与 [WaterReaction] 同理）
 */
data class FluidPairRule(
    val other: String,
    val selfSource: Boolean?,
    val otherSource: Boolean?,
    val product: () -> Block,
)

/**
 * 流体对流体反应表（用户口径，2026-09-25）。
 *
 * ## 余烬 × 浮霜
 *
 * | 余烬那格 | 浮霜那格 | 结果 | 谁变成方块 |
 * | --- | --- | --- | --- |
 * | 源 | 源 | 黑石 | **余烬**（浮霜保留） |
 * | 其它任意组合（含"源 + 流动"） | | 石头 | **浮霜**（余烬保留） |
 *
 * 换成规则就是这样：
 *
 * - 余烬侧只认**双源**这一档 → 出黑石；
 * - 浮霜侧写两条"只要不是双源就出石头"的规则（自己流动 / 对手流动各一条），
 *   两条都不命中时正好是双源，交给余烬侧处理，不会两边同时反应。
 *
 * 判定顺序由列表顺序决定，**第一条命中即生效**，所以浮霜那两条写得再松也不会越权。
 *
 * ⚠️ 只在**世界流体**之间生效（`block/ReactiveLiquidBlock` 里按流体状态逐格判定）；
 * 炼药锅里的混合是配方的事，不走这里。
 */
object FluidPairReactions {

    private val EMBER: String = AddonFluidSpecs.EMBER_FLUID.name
    private val FROST: String = AddonFluidSpecs.FROST_FLUID.name

    val TABLE: Map<String, List<FluidPairRule>> = mapOf(
        // ── 余烬：双源 → 黑石（变的是自己这格）──
        EMBER to listOf(
            FluidPairRule(FROST, selfSource = true, otherSource = true) { Blocks.BLACKSTONE },
        ),
        // ── 浮霜：只要不是双源就凝成石头（变的是自己这格）；双源那一档归余烬侧 ──
        FROST to listOf(
            FluidPairRule(EMBER, selfSource = false, otherSource = null) { Blocks.STONE },
            FluidPairRule(EMBER, selfSource = null, otherSource = false) { Blocks.STONE },
        ),
    )

    /** 该流体参与的规则（没参与就是空表，方块用不上这套判定） */
    fun rulesFor(fluidName: String): List<FluidPairRule> = TABLE[fluidName].orEmpty()

    /** 该流体是否参与任何"流体对流体"反应 */
    fun isInvolved(fluidName: String): Boolean = TABLE.containsKey(fluidName)
}
