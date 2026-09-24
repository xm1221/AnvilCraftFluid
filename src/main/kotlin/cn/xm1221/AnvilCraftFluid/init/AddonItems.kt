package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid.Companion.REGISTRUM

/**
 * 物品注册入口。
 *
 * 本模组的物品目前全部由 [AddonFluids] 在注册流体时连带产出
 * （`<name>_bucket` 桶），这里只保留创造标签页的默认归属设置。
 */
class AddonItems {
    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    companion object {
        init {
            REGISTRUM.defaultCreativeTab(AddonItemGroups.ADDON_ITEMS.key)
        }

        fun register() {
        }
    }
}
