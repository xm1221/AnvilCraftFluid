package cn.xm1221.AnvilCraftFluid.event

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import cn.xm1221.AnvilCraftFluid.fluid.WaterReactions
import net.minecraft.core.registries.BuiltInRegistries
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent

/**
 * 启动自检：把水反应表逐条解析并打进日志。
 *
 * 水反应只发生在世界里，平时没有可观测证据；一旦"反应不生效"，
 * 光看代码很难判断是**表没查到**、**产物解析失败**还是**方块没触发**。
 * 这段自检在 `FMLCommonSetupEvent`（注册表已填充，解析上游方块安全）跑一次，
 * 日志里就能直接对照：
 *
 * ```
 * Water reaction table (10 fluids):
 *   molten_iron     : source -> minecraft:iron_ore          | flowing -> (不反应)
 *   molten_ruby     : source -> minecraft:granite           | flowing -> minecraft:granite
 * ```
 *
 * 顺便覆盖一个真实的坑：产物表里存的是工厂 lambda，若哪天有人在里面直接
 * `ModBlocks.XXX.get()` 而不是包进 lambda，这里会立刻抛 `unbound value`，
 * 而不是等到玩家倒水时才崩。
 */
object ReactionTableCheck {

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        event.enqueueWork {
            val lines = AddonFluids.REGISTERED.mapNotNull { registered ->
                val reaction = WaterReactions.reactionFor(registered.spec.name) ?: return@mapNotNull null
                val source = BuiltInRegistries.BLOCK.getKey(reaction.sourceProduct())
                val flowing = reaction.flowingProduct?.invoke()
                    ?.let { BuiltInRegistries.BLOCK.getKey(it).toString() }
                    ?: "(不反应)"
                "  %-22s source -> %-30s | flowing -> %s".format(registered.spec.name, source, flowing)
            }

            AnvilCraftFluid.LOGGER.debug(
                "Water reaction table ({} fluid(s)):\n{}",
                lines.size,
                lines.joinToString("\n"),
            )
        }
    }
}
