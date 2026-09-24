package cn.xm1221.AnvilCraftFluid.init

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.fluid.AddonFluidSpecs
import dev.dubhe.anvilcraft.init.item.ModItemGroups
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

class AddonItemGroups {
    companion object {
        val DEFERRED_REGISTER: DeferredRegister<CreativeModeTab?> = DeferredRegister.create<CreativeModeTab>(
            Registries.CREATIVE_MODE_TAB,
            AnvilCraftFluid.MOD_ID
        )

        val ADDON_ITEMS: DeferredHolder<CreativeModeTab?, CreativeModeTab?> =
            DEFERRED_REGISTER.register<CreativeModeTab?>(
                "addon_items",
                Supplier {
                    CreativeModeTab.builder()
                        .icon {
                            AddonFluids.byName(AddonFluidSpecs.MOLTEN_RUBY.name)
                                ?.bucket
                                ?.defaultInstance
                                ?: ItemStack.EMPTY
                        }
                        .displayItems { _, _ -> }
                        .title(
                            AnvilCraftFluid.REGISTRUM.addLang(
                                "itemGroup",
                                AnvilCraftFluid.of("addon_items"),
                                "AnvilCraft: Fluid"
                            )
                        )
                        .withTabsBefore(ModItemGroups.ANVILCRAFT_BUILD_BLOCK.id)
                        .build()
                }
            )

        fun register(modEventBus: IEventBus) {
            DEFERRED_REGISTER.register(modEventBus)
        }
    }
}
