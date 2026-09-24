package cn.xm1221.AnvilCraftFluid

import dev.anvilcraft.lib.v2.config.BoundedDiscrete
import dev.anvilcraft.lib.v2.config.Comment
import dev.anvilcraft.lib.v2.config.Config

/**
 * 模组配置。
 *
 * | 流体 | 行为 | 实现位置 |
 * | --- | --- | --- |
 * | 余烬液体 `ember_fluid` | **接触即修复**带 `FIRE_REFORGING` 的余烬装备，比岩浆快，不耗流体 | `block/ReforgingFluidBlock`（世界流体）+ `block/AddonCauldronBlock`（余烬锅） |
 * | 熔融金 `molten_gold` | 洗掉物品上的诅咒附魔，同时产出等量**诅咒金液体** | `event/CauldronItemReactions`（大型炼药锅逐 tick） |
 * | 浮霜液体 `frost_fluid` | 洗掉物品上的全部附魔 | 同上 |
 *
 * 余烬的速率参照上游 `ItemEntityMixin` 的方块表：火 = 2、灵魂火 = 5、岩浆/岩浆锅 = 10，
 * 且**完全不消耗燃料**；余烬取 [AddonConfig.emberRepairPerTick]（默认 20，岩浆的两倍）。
 */
@Config(name = AnvilCraftFluid.MOD_ID)
class AddonConfig {

    @Comment("余烬液体每 tick 修复的耐久点数（上游岩浆自修复是 10；余烬不消耗自身流体）")
    @BoundedDiscrete(max = 200.0, min = 1.0)
    var emberRepairPerTick: Int = 20

    @Comment("熔融金每洗掉 1 条诅咒附魔消耗的 mB，并按同量产出诅咒金液体")
    @BoundedDiscrete(max = 1000.0, min = 1.0)
    var goldFluidPerCurse: Int = 500

    @Comment("浮霜液体每洗掉 1 条附魔消耗的 mB（产出量另按上游公式 2^(等级-1) mB 算）")
    @BoundedDiscrete(max = 1000.0, min = 1.0)
    var frostFluidPerEnchantment: Int = 250
}
