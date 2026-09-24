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
 * 标签归属由 [cn.xm1221.AnvilCraftFluid.fluid.FluidFamily] 决定：
 * 宝石 / 金属进 [MOLTEN] + 各自族标签；功能流体（浮霜 / 余烬 / 诅咒金）只进 [SPECIAL]。
 *
 * 注意：[AddonFluids] 里对每个流体调用 `FluidBuilder.tag(...)` 时，
 * Registrum 会同时给**源流体**和流动流体打上标签。
 */
object AddonFluidTags {

    /** 全部熔融材料（宝石 + 金属） */
    val MOLTEN: TagKey<Fluid> = of("molten")

    /** 熔融宝石 */
    val MOLTEN_GEM: TagKey<Fluid> = of("molten_gem")

    /** 熔融金属 */
    val MOLTEN_METAL: TagKey<Fluid> = of("molten_metal")

    /** 功能流体（浮霜 / 余烬 / 诅咒金） */
    val SPECIAL: TagKey<Fluid> = of("special")

    /** 按路径取（或新建）本模组的流体标签 */
    fun of(path: String): TagKey<Fluid> =
        TagKey.create(Registries.FLUID, AnvilCraftFluid.of(path))
}
