package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block

/**
 * 本模组的**方块标签**常量。
 *
 * 标签内容一律走 datagen 生成（见 `data/block/AddonBlockTagHandler`），
 * 这里只放运行时代码（`FluidSpec` 等）需要引用的 [TagKey]。
 */
object AddonBlockTags {
    /**
     * **红石树脂胶体冲不掉的红石元件**（MC + 上游 AnvilCraft）。
     *
     * 由 `FluidSpec.REDSTONE_RESIN` 的 `washResistantTags` 引用，
     * 判定见 `mixin/FlowingFluidMixin`。想增删内容改 `AddonBlockTagHandler` 即可。
     */
    val WASH_PROOF_REDSTONE: TagKey<Block> = blockTag("wash_proof/redstone")

    /** 上游现成的滑轨标签（滑轨 + 动力/探测/激活四种），被 [WASH_PROOF_REDSTONE] 引用 */
    val ANVILCRAFT_SLIDING_RAILS: TagKey<Block> = TagKey.create(
        Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("anvilcraft", "sliding_rails"),
    )

    private fun blockTag(name: String): TagKey<Block> =
        TagKey.create(Registries.BLOCK, AnvilCraftFluid.of(name))
}
