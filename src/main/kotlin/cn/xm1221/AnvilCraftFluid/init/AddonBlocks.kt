package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid.Companion.REGISTRUM

/**
 * 方块注册入口。
 *
 * 本模组的方块目前全部由 [AddonFluids] 在注册流体时连带产出
 * （`<name>` 液体方块与 `<name>_cauldron` 炼药锅），
 * 这里只保留创造标签页的默认归属设置，方便以后新增独立方块。
 */
class AddonBlocks {
    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    companion object {
        init {
            REGISTRUM.defaultCreativeTab(AddonItemGroups.ADDON_ITEMS.key)
        }

        fun register() {
        }
    }
}
