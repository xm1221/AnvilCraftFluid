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
        }
    }
}
