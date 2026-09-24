package cn.xm1221.AnvilCraftFluid.fluid

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * 流体遇水凝固反应表。
 *
 * 实现见 [cn.xm1221.AnvilCraftFluid.block.ReactiveLiquidBlock]：
 * 表里的流体注册时会用那个自定义液体方块，碰水就把自己那格换成 [TABLE] 里的方块。
 *
 * 语义对齐上游 AnvilCraft 的 `melt_gem` + 水（`ModFluids.registerFluidInteractions`）
 * 以及原版岩浆 + 水：**反应后水保留**，只凝固自己这一格。
 *
 * 键是 [FluidSpec.name]；不在表里的流体（目前是全部熔融金属）遇水不发生任何事。
 */
object WaterReactions {

    /**
     * 流体名 → 遇水生成的方块。
     *
     * 对应开发计划里的"流体反应表"：
     *
     * | 熔融流体 | 水 → |
     * | --- | --- |
     * | 红宝石 | 花岗岩 |
     * | 石英 | 闪长岩 |
     * | 蓝宝石 / 黄玉 / 绿宝石 | 安山岩 |
     * | 紫水晶 | 方解石 |
     */
    val TABLE: Map<String, Block> = mapOf(
        "molten_ruby" to Blocks.GRANITE,
        "molten_quartz" to Blocks.DIORITE,
        "molten_sapphire" to Blocks.ANDESITE,
        "molten_topaz" to Blocks.ANDESITE,
        "molten_emerald" to Blocks.ANDESITE,
        "molten_amethyst" to Blocks.CALCITE,
    )

    /** 该流体遇水生成什么方块；null 表示不反应 */
    fun productOf(fluidName: String): Block? = TABLE[fluidName]
}
