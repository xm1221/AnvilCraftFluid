package cn.xm1221.AnvilCraftFluid

import dev.anvilcraft.lib.v2.config.BoundedDiscrete
import dev.anvilcraft.lib.v2.config.Comment
import dev.anvilcraft.lib.v2.config.Config

/**
 * 模组配置。
 *
 * 这三个行为都跑在**大型炼药锅**上，逐服务器 tick 检查锅里的流体与输入槽物品
 * （见 `event/CauldronItemReactions.kt`）：
 *
 * | 流体 | 行为 |
 * | --- | --- |
 * | 余烬液体 `ember_fluid` | 给带 `FIRE_REFORGING` 的余烬装备加速修复 |
 * | 熔融金 `molten_gold` | 洗掉物品上的诅咒附魔，同时产出等量**诅咒金液体** |
 * | 浮霜液体 `frost_fluid` | 洗掉物品上的全部附魔 |
 *
 * 上游岩浆自修复的速率是 `FireReforgingUtil.LAVA_REPAIR_PER_TICK = 10` 且不耗流体，
 * 我们的余烬液体默认 32/tick 但**按耐久消耗流体**。
 */
@Config(name = AnvilCraftFluid.MOD_ID)
class AddonConfig {

    @Comment("余烬液体每 tick 修复的耐久点数（上游岩浆自修复是 10）")
    @BoundedDiscrete(max = 200.0, min = 1.0)
    var emberRepairPerTick: Int = 32

    @Comment("余烬液体每修复 1 点耐久消耗的 mB（一锅 = 1000 mB）")
    @BoundedDiscrete(max = 100.0, min = 1.0)
    var emberFluidPerDurability: Int = 1

    @Comment("熔融金每洗掉 1 条诅咒附魔消耗的 mB，并按同量产出诅咒金液体")
    @BoundedDiscrete(max = 1000.0, min = 1.0)
    var goldFluidPerCurse: Int = 500

    @Comment("浮霜液体每洗掉 1 条附魔消耗的 mB")
    @BoundedDiscrete(max = 1000.0, min = 1.0)
    var frostFluidPerEnchantment: Int = 250
}
