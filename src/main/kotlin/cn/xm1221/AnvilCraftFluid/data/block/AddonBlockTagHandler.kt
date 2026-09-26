package cn.xm1221.AnvilCraftFluid.data.block

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.init.AddonBlockTags
import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.common.data.BlockTagsProvider
import net.neoforged.neoforge.common.data.ExistingFileHelper
import java.util.concurrent.CompletableFuture

/**
 * 方块标签 datagen。
 *
 * 目前只有一份：`anvilcraft_fluid:wash_proof/redstone` —— "红石树脂胶体冲不掉的红石元件"，
 * 由 `FluidSpec.REDSTONE_RESIN.washResistantTags` 引用，判定见 `mixin/FlowingFluidMixin`。
 *
 * 上游/跨模组的方块用 `addOptional*`：万一依赖不在也能生成，只在日志里提一句，
 * 而**不会**因为缺了某个方块让整个 datagen 挂掉。
 *
 * ⚠️ 本模组的数据一律走 datagen（见 `AddonDatagen`），**不要**再手写
 * `src/main/resources/data/.../tags/...json` —— 两边同名会互相覆盖，排查起来很费劲。
 */
class AddonBlockTagHandler(
    output: PackOutput,
    lookupProvider: CompletableFuture<HolderLookup.Provider>,
    existingFileHelper: ExistingFileHelper?,
) : BlockTagsProvider(output, lookupProvider, AnvilCraftFluid.MOD_ID, existingFileHelper) {

    override fun addTags(provider: HolderLookup.Provider) {
        val appender = tag(AddonBlockTags.WASH_PROOF_REDSTONE)

        appender
            // ① 原版现成的分组：按钮（木/石）、压力板（木/石）、铁轨（普通/动力/探测/激活）。
            //    AnvilCraft 自己把它的 14 个压力板加进了 `minecraft:pressure_plates`，
            //    所以这一条顺带覆盖上游压力板，不需要额外写。
            .addTag(BlockTags.BUTTONS)
            .addTag(BlockTags.PRESSURE_PLATES)
            .addTag(BlockTags.RAILS)
            // ② 上游现成的分组：滑轨 + 动力/探测/激活滑轨
            .addOptionalTag(AddonBlockTags.ANVILCRAFT_SLIDING_RAILS)
            // ③ 上游没有标签的那几个，按注册名补
            .addOptional(ResourceLocation.fromNamespaceAndPath("anvilcraft", "redstone_wire"))
            .addOptional(ResourceLocation.fromNamespaceAndPath("anvilcraft", "redstone_dice"))
            .addOptional(ResourceLocation.fromNamespaceAndPath("anvilcraft", "big_red_button"))

        // ④ 原版红石元件。固体那几个本来也冲不掉，列全是为了以后判定变了也不出事。
        //    这里按注册名（ResourceKey）加：Kotlin 侧 `add(Block...)` 的重载解析会落到
        //    父类那个只收 ResourceKey 的重载上，绕开它反而最省事。
        listOf(
            Blocks.REDSTONE_WIRE,
            Blocks.REDSTONE_TORCH,
            Blocks.REDSTONE_WALL_TORCH,
            Blocks.LEVER,
            Blocks.TRIPWIRE,
            Blocks.TRIPWIRE_HOOK,
            Blocks.REPEATER,
            Blocks.COMPARATOR,
            Blocks.OBSERVER,
            Blocks.PISTON,
            Blocks.STICKY_PISTON,
            Blocks.REDSTONE_BLOCK,
            Blocks.REDSTONE_LAMP,
            Blocks.DAYLIGHT_DETECTOR,
            Blocks.TARGET,
            Blocks.SCULK_SENSOR,
            Blocks.CALIBRATED_SCULK_SENSOR,
            Blocks.LIGHTNING_ROD,
            Blocks.NOTE_BLOCK,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.HOPPER,
            Blocks.LECTERN,
            Blocks.CRAFTER,
            Blocks.TRAPPED_CHEST,
        ).forEach { appender.add(it.builtInRegistryHolder().key()) }
    }
}
