package cn.xm1221.AnvilCraftFluid.data.lang

import cn.xm1221.AnvilCraftFluid.AddonConfig
import dev.anvilcraft.lib.v2.config.ConfigData
import dev.anvilcraft.lib.v2.registrum.providers.RegistrumLangProvider

class AddonLangHandler {
    companion object {
        /**
         * 语言文件初始化
         *
         * @param provider 提供器
         */
        fun init(provider: RegistrumLangProvider) {
            ConfigData.readConfigClass(provider, AddonConfig::class.java)
            addManualEnglish(provider)
        }

        /**
         * 补齐**英文**里「手写」的那批键。
         *
         * ## 为什么需要这一步
         *
         * 方块/物品/流体/炼药锅的名字由 Registrum 从注册名自动生成
         * （`molten_ruby` → "Molten Ruby"），这些不用管；但 tooltip、死亡讯息、
         * JEI 类别文案和配置项说明**没有注册名可推**，中文那份是手写在
         * `src/main/resources/assets/anvilcraft_fluid/lang/zh_cn.json` 里的，
         * 而 datagen 只生成 `en_us`——于是英文客户端上这批键会退回显示键名。
         * 这里按同一批键把英文补上。
         *
         * ## ⚠️ 配置项说明不在这里
         *
         * 配置项说明的键（`anvilcraft_fluid.configuration.*.tooltip`）是
         * [ConfigData.readConfigClass] 按 [cn.xm1221.AnvilCraftFluid.AddonConfig] 上
         * `@Comment` 的**原文**写进去的，而 Registrum 的语言 provider 对重复键
         * 直接抛 `IllegalStateException: Duplicate translation key`（不是覆盖），
         * 所以这里没法再补一份英文。要让英文端也显示英文说明，只能改 `@Comment`
         * 的文字本身——中文说明由手写的 `zh_cn.json` 提供，不受影响。
         *
         * ## ⚠️ 与 zh_cn.json 的同步
         *
         * 这里只写英文。中文仍然只维护 `zh_cn.json` 那一份；
         * 以后新增 tooltip / 死亡讯息 / JEI 文案时，**两份都要加**，
         * 否则中英会各自缺键（缺失的那一侧会显示键名）。
         */
        private fun addManualEnglish(provider: RegistrumLangProvider) {
            // —— 流体 tooltip ——
            // 键名 = `tooltip.anvilcraft_fluid.<注册名>`，取值逻辑见 client/AddonFluidTooltips.kt
            provider.add("tooltip.anvilcraft_fluid.molten_ruby", "Molten ruby, imbued with the power of fire")
            provider.add("tooltip.anvilcraft_fluid.molten_sapphire", "Molten sapphire, imbued with the power of frost")
            provider.add("tooltip.anvilcraft_fluid.molten_topaz", "Molten topaz, imbued with the power of lightning")
            provider.add("tooltip.anvilcraft_fluid.molten_tungsten", "Molten tungsten, the raw material of netherite")
            provider.add(
                "tooltip.anvilcraft_fluid.molten_royal_steel",
                "Molten royal steel, brimming with gem magic",
            )
            provider.add("tooltip.anvilcraft_fluid.molten_uranium", "Molten uranium, glowing with an ominous green light")
            provider.add(
                "tooltip.anvilcraft_fluid.frost_fluid",
                "A wondrous fluid born from freezing magic, with curious properties",
            )
            provider.add(
                "tooltip.anvilcraft_fluid.ember_fluid",
                "A fluid condensed from ember magic, scorching hot and refusing to go out",
            )
            provider.add(
                "tooltip.anvilcraft_fluid.redstone_resin",
                "A redstone-and-resin mixture that carries redstone signals",
            )
            provider.add("tooltip.anvilcraft_fluid.cursed_gold_fluid", "Molten cursed gold, giving off an ominous air")

            // —— 死亡讯息 ——（`$` 在 Kotlin 字符串里必须转义，否则会被当成模板）
            provider.add("death.attack.anvilcraft_fluid.ember", "%1\$s was burnt to ashes by Ember Fluid")
            provider.add(
                "death.attack.anvilcraft_fluid.ember.player",
                "%1\$s was pushed into Ember Fluid by %2\$s",
            )

            // —— 手册（JEI/藿香）里的展示文案 ——
            provider.add("gui.anvilcraft_fluid.guide.amount", "%1\$smB")
            provider.add("gui.anvilcraft_fluid.guide.extra_fluid", "Also requires %1\$smB of %2\$s")
            provider.add("gui.anvilcraft_fluid.category.cauldron_reaction", "Cauldron Fluid Reaction")
            provider.add(
                "gui.anvilcraft_fluid.cauldron_reaction.frost.0",
                "Washes every enchantment off the item",
            )
            provider.add(
                "gui.anvilcraft_fluid.cauldron_reaction.frost.1",
                "Each enchantment yields 2^(level-1) mB of Liquid Enchantment",
            )
            provider.add("gui.anvilcraft_fluid.cauldron_reaction.curse.0", "Washes the curses off the item")
            provider.add(
                "gui.anvilcraft_fluid.cauldron_reaction.curse.1",
                "Each curse yields the same amount of Cursed Gold Fluid",
            )
            provider.add(
                "gui.anvilcraft_fluid.cauldron_reaction.anvil_strike",
                "Triggered by dropping an anvil on the Large Cauldron",
            )
            provider.add(
                "gui.anvilcraft_fluid.info.ember_fluid",
                "Items standing in Ember Fluid are reforged: faster than lava, " +
                    "and the fluid itself is not consumed",
            )
        }
    }
}
