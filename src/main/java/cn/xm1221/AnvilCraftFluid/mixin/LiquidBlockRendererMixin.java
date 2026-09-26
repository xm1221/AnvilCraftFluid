package cn.xm1221.AnvilCraftFluid.mixin;

import cn.xm1221.AnvilCraftFluid.client.FluidOutlineRenderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管本模组流体的渲染：声明了描边的流体不再走原版流程，
 * 改由 {@link FluidOutlineRenderer} 绘制"缩小的流体 + 内壳"。
 *
 * 方法签名（NeoForge 1.21.1）：
 * tesselate(BlockAndTintGetter, BlockPos, VertexConsumer, BlockState, FluidState)
 */
@Mixin(LiquidBlockRenderer.class)
public class LiquidBlockRendererMixin {

    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void anvilcraftfluid$replaceWithOutline(
            BlockAndTintGetter level,
            BlockPos pos,
            VertexConsumer buffer,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        if (FluidOutlineRenderer.isOutlined(fluidState)) {
            FluidOutlineRenderer.emit(level, pos, buffer, fluidState);
            ci.cancel();
        }
    }
}