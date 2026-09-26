package cn.xm1221.AnvilCraftFluid.mixin;

import cn.xm1221.AnvilCraftFluid.init.AddonFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "某些流体不冲掉某些方块"：在"流体能不能流进那一格"的闸门上拦
 * （清单见 {@code FluidSpec.washResistant} / {@code FluidSpec.washResistantTags}）。
 *
 * 原版 {@code FlowingFluid#canSpreadTo} 是三个条件的与：
 * <pre>
 *   toFluidState.canBeReplacedWith(level, toPos, fluid, direction)
 *       &amp;&amp; this.canPassThroughWall(...)
 *       &amp;&amp; this.canHoldFluid(...)
 * </pre>
 * 只要 {@code canBeReplacedWith} 为 false，流体就<strong>根本不会流进那一格</strong>：
 * 方块不会被替换、也不会掉物品（掉落发生在之后的 {@code spreadTo → beforeDestroyingBlock} 里）。
 * 在 HEAD 处直接取消即可，不需要改任何状态。
 *
 * 三条自我约束：
 * <ul>
 *   <li>只对<strong>本模组配了清单的流体</strong>生效——{@code AddonFluids.washProofFor} 返回 null
 *       就是没配，原版与其它模组的流体一律原样放行。</li>
 *   <li><strong>目标格子里已经是流体时一律放过</strong>：流体冲掉流体是"流体对流体"反应
 *       （浮霜源 + 余烬源 → 黑石 那类）的入口，不能被这里拦断。</li>
 *   <li>标签是<strong>运行时现查</strong>（{@code BlockState#is(TagKey)}）：标签由数据包加载，
 *       注册期解析只会拿到空集。</li>
 * </ul>
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void anvilcraft_fluid$respectWashResistant(
            BlockGetter level,
            BlockPos fromPos,
            BlockState fromBlockState,
            Direction direction,
            BlockPos toPos,
            BlockState toBlockState,
            FluidState toFluidState,
            Fluid fluid,
            CallbackInfoReturnable<Boolean> cir) {
        AddonFluids.WashProof proof = AddonFluids.washProofFor(fluid);
        if (proof == null) {
            return;
        }
        BlockState target = level.getBlockState(toPos);
        // 目标格子里是流体 → 放过（混合反应的入口）
        if (!target.getFluidState().isEmpty()) {
            return;
        }
        if (proof.getBlocks().contains(target.getBlock())) {
            cir.setReturnValue(false);
            return;
        }
        for (TagKey<Block> tag : proof.getTags()) {
            if (target.is(tag)) {
                cir.setReturnValue(false);
                return;
            }
        }
        if (!proof.getIds().isEmpty()
                && proof.getIds().contains(BuiltInRegistries.BLOCK.getKey(target.getBlock()))) {
            cir.setReturnValue(false);
        }
    }
}
