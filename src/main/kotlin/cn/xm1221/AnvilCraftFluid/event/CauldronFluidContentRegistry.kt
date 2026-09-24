package cn.xm1221.AnvilCraftFluid.event

import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.fluids.RegisterCauldronFluidContentEvent

/**
 * 把我们的炼药锅与流体登记进 NeoForge 的 [net.neoforged.neoforge.fluids.CauldronFluidContent]。
 *
 * 这是"锅能被上游认出来"的关键一步，登记之后：
 * - 用桶往锅里倒流体 / 从锅里舀流体由 NeoForge 自动处理；
 * - AnvilCraft 的 `CauldronUtil`（层数换算、`fill` / `drain` / `fullState`）认得我们的锅；
 * - `FluidContainerLookup`（流体网络）把我们的锅当成流体端点；
 * - JEI 的固液反应页（`SolidLiquidCategory`）会把我们的锅流体纳入展示。
 *
 * 最后一个参数 `levelProperty` 传 null 表示"满锅"（没有层数状态，一锅就是 1000 mB），
 * 与 AnvilCraft 的 `MELT_GEM_CAULDRON` 做法一致。
 *
 * 注意：[RegisterCauldronFluidContentEvent] 实现了 `IModBusEvent`，
 * 所以本监听器在 mod 事件总线上注册（见 [cn.xm1221.AnvilCraftFluid.AnvilCraftFluid] 的构造函数）。
 */
object CauldronFluidContentRegistry {

    @SubscribeEvent
    fun registerCauldronFluidContent(event: RegisterCauldronFluidContentEvent) {
        AddonFluids.REGISTERED.forEach { registered ->
            event.register(
                registered.cauldron.get(),
                registered.source,
                registered.cauldronAmount,
                registered.levelProperty,
            )
        }
    }
}
