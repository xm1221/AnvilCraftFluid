package cn.xm1221.AnvilCraftFluid

import dev.anvilcraft.lib.v2.config.BoundedDiscrete
import dev.anvilcraft.lib.v2.config.Comment
import dev.anvilcraft.lib.v2.config.Config

@Config(name = AnvilCraftFluid.MOD_ID)
class AddonConfig {
    @Comment("Whether to print fluid-registry debug info on common setup")
    var debugFluidLog: Boolean = false

    @Comment("Fluid reaction re-check interval in ticks")
    @BoundedDiscrete(max = 200.0, min = 1.0)
    var reactionInterval: Int = 10
}
