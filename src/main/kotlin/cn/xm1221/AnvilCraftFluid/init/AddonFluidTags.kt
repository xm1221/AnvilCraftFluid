package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.material.Fluid

/**
 * 本模组自建的流体标签。
 *
 * 用途：
 * - 配方 / 反应里写「任意熔融金属」「任意熔融宝石」这类条件，不用逐个列出；
 * - 让其他模组（含 AnvilCraft 本体）能用标签选中我们的流体；
 * - 顺带成为**注册结果的可核对产物**：`runData` 后
 *   `data/anvilcraft_fluid/tags/fluid/` 下的 json 会列出全部已注册流体。
 *
 * 注意：[AddonFluids] 里对每个流体调用 `FluidBuilder.tag(...)` 时，
 * Registrum 会同时给**源流体**和流动流体打上标签。
 */
object AddonFluidTags {

    /** 所有熔融流体 */
    val MOLTEN: TagKey<Fluid> = create("molten")

    /** 熔融宝石（红宝石 / 石英 / 蓝宝石 / 黄玉 / 绿宝石） */
    val MOLTEN_GEM: TagKey<Fluid> = create("molten_gem")

    /** 熔融金属（铁 / 金 / 铜 / 钨 / 皇家钢） */
    val MOLTEN_METAL: TagKey<Fluid> = create("molten_metal")

    private fun create(path: String): TagKey<Fluid> =
        TagKey.create(Registries.FLUID, AnvilCraftFluid.of(path))
}
