package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid.Companion.REGISTRUM
import cn.xm1221.AnvilCraftFluid.fluid.AddonFluidSpecs
import dev.dubhe.anvilcraft.init.item.ModItemGroups
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * 创造模式物品栏：`anvilcraft_fluid:addon_items`（"铁砧工艺：流体扩展"）。
 *
 * ## 为什么必须显式 `defaultCreativeTab`
 *
 * Registrum 的 `AbstractRegistrum` 在**创建物品 builder 时**会读一次
 * `defaultCreativeModeTab`，并把它写进该物品的 `tab(...)`：
 *
 * ```java
 * // AbstractRegistrum#item(...)
 * .transform(builder -> this.defaultCreativeModeTab == null ? builder : builder.tab(this.defaultCreativeModeTab))
 * ```
 *
 * 也就是说：**必须在任何物品被注册之前把默认标签页设好**，
 * 否则物品只会落到 `CreativeModeTabs.SEARCH`（原版搜索页），
 * 我们自己的栏里空空如也——而且不会有任何报错。
 *
 * 本模组的物品（14 个桶）是在 [AddonFluids] 初始化时由
 * `FluidBuilder.bucket()` → `REGISTRUM.item(...)` 创建的，
 * 所以 [register] 必须在 `AddonFluids.register()` **之前**调用；
 * 默认标签页的设置写在下面的 `init` 块里（`ADDON_ITEMS` 声明之后，
 * 否则 Kotlin 的初始化顺序会让 `ADDON_ITEMS` 还是 null）。
 *
 * 炼药锅没有物品形式（与原版 / AnvilCraft 一致：只能由桶倒进炼药锅产生），
 * 所以栏里只有 14 个桶（6 宝石 + 5 金属 + 3 功能流体），没有锅。
 */
class AddonItemGroups {
    companion object {
        private const val TAB_NAME: String = "addon_items"

        /** 标签页的资源键；[REGISTRUM.defaultCreativeTab] 与注册表都用它 */
        val TAB_KEY: ResourceKey<CreativeModeTab> =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, AnvilCraftFluid.of(TAB_NAME))

        val DEFERRED_REGISTER: DeferredRegister<CreativeModeTab> =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AnvilCraftFluid.MOD_ID)

        val ADDON_ITEMS: DeferredHolder<CreativeModeTab, CreativeModeTab> =
            DEFERRED_REGISTER.register(
                TAB_NAME,
                Supplier {
                    CreativeModeTab.builder()
                        .icon {
                            AddonFluids.byName(AddonFluidSpecs.MOLTEN_RUBY.name)
                                ?.bucket
                                ?.defaultInstance
                                ?: ItemStack.EMPTY
                        }
                        // 内容由 Registrum 依据每个条目的 defaultCreativeTab 自动填入，
                        // 这里不需要手写 displayItems
                        .displayItems { _, _ -> }
                        .title(
                            REGISTRUM.addLang(
                                "itemGroup",
                                AnvilCraftFluid.of(TAB_NAME),
                                "AnvilCraft: Fluid"
                            )
                        )
                        // 排在 AnvilCraft 自己的标签页旁边
                        .withTabsBefore(ModItemGroups.ANVILCRAFT_BUILD_BLOCK.id)
                        .build()
                }
            )

        init {
            // ⚠️ 顺序关键：必须在任何 REGISTRUM.item(...) 之前执行
            REGISTRUM.defaultCreativeTab(TAB_KEY)
            AnvilCraftFluid.LOGGER.debug("Default creative tab set to {}", TAB_KEY.location())
        }

        fun register(modEventBus: IEventBus) {
            // 触发 companion 初始化（即上面 init 块里的 defaultCreativeTab）
            DEFERRED_REGISTER.register(modEventBus)
        }
    }
}
