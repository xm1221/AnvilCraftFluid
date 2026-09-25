package cn.xm1221.AnvilCraftFluid.client

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonFluids
import net.minecraft.ChatFormatting
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent

/**
 * 桶（以及任何将来给流体加的物品）的说明文字。
 *
 * 文案**手写在语言文件**里，按序号取，缺哪行就跳过哪行：
 *
 * | 键 | 内容 |
 * | --- | --- |
 * | `tooltip.anvilcraft_fluid.<name>` | 第一行 |
 * | `tooltip.anvilcraft_fluid.<name>.1` | 第二行（可选） |
 * | `tooltip.anvilcraft_fluid.<name>.2` | 第三行（可选） |
 *
 * ## 写什么
 *
 * 照上游的口气：**一句风味介绍就够**，写它"是什么、看起来怎样"，
 * 不写数值、不写机制（那些交给手册），不加句号。例如：
 *
 * - `熔融的皇家钢，富含宝石魔力`
 * - `冰冻魔力化为的神奇流体，似乎具有奇妙的性质`
 *
 * 需要补充时再往后加 `.1`、`.2`（上游把详情放在"按住 Shift"那一档，
 * 我们目前不做两级）。
 *
 * 因此加文案**不需要改代码**：在 `lang/zh_cn.json` 里补键即可；
 * 某种流体不想有说明，就不写它的键（[I18n.exists] 查不到就跳过）。
 *
 * 走 [ItemTooltipEvent] 而不是覆写物品类：桶是 Registrum 建的 `BucketItem`，
 * 我们拿不到它的类去覆写方法，而替换掉 Registrum 的桶会连带丢掉它的模型/染色注册。
 *
 * ⚠️ [I18n] 是**客户端专用类**，所以本类必须挂在 `Dist.CLIENT` 上
 * （见 [EventBusSubscriber] 的 `value`），否则专用服务器加载时会 NoClassDefFoundError。
 */
@EventBusSubscriber(modid = AnvilCraftFluid.MOD_ID, value = [Dist.CLIENT])
object AddonFluidTooltips {

    /** 最多读几行说明 */
    private const val MAX_LINES = 3

    @SubscribeEvent
    @JvmStatic
    fun onItemTooltip(event: ItemTooltipEvent) {
        val stack = event.itemStack
        val registered = AddonFluids.REGISTERED.firstOrNull { it.bucket === stack.item } ?: return

        val base = "tooltip.${AnvilCraftFluid.MOD_ID}.${registered.spec.name}"
        for (index in 0 until MAX_LINES) {
            val key = if (index == 0) base else "$base.$index"
            // 用 continue 而不是 break：允许只写第 1、3 行这种"跳行"写法
            if (!I18n.exists(key)) continue
            event.toolTip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY))
        }
    }
}
