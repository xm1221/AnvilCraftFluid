package cn.xm1221.AnvilCraftFluid.mixin;

import cn.xm1221.AnvilCraftFluid.client.FluidOutlineRenderer;
import cn.xm1221.AnvilCraftFluid.client.FluidVertexCapture;
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
 * 接管本模组流体的渲染：声明了描边的流体不再把原版那批顶点直接写进画面，
 * 而是先让原版把这一格照常算一遍，把顶点收进 {@link FluidVertexCapture}，
 * 再交给 {@link FluidOutlineRenderer} 做"水平内缩 + 内壳"。
 *
 * 拿原版顶点的手法：把 buffer 换成捕获器，**不取消**原版调用，而是递归调一次自己；
 * 内层调用被重入标记放过，于是原版真的跑了，产物落在捕获器里。
 * 这样液面完全是原版自己画的那份，我们不再另算高度。
 *
 * 方法签名（NeoForge 1.21.1）：
 * tesselate(BlockAndTintGetter, BlockPos, VertexConsumer, BlockState, FluidState)
 */
@Mixin(LiquidBlockRenderer.class)
public class LiquidBlockRendererMixin {

    /** 重入放行标记：为真时说明我们已在处理这一格，让原版真的画进捕获器 */
    private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void anvilcraftfluid$captureVanillaFluid(
            BlockAndTintGetter level,
            BlockPos pos,
            VertexConsumer buffer,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        if (CAPTURING.get()) return;                              // 内层：原版正常跑
        if (!FluidOutlineRenderer.isOutlined(fluidState)) return;  // 不是我们的流体：原样

        FluidVertexCapture cap = FluidVertexCapture.get();
        cap.reset();
        CAPTURING.set(Boolean.TRUE);
        try {
            ((LiquidBlockRenderer) (Object) this).tesselate(level, pos, cap, blockState, fluidState);
        } finally {
            CAPTURING.set(Boolean.FALSE);
        }

        FluidOutlineRenderer.emit(level, pos, buffer, fluidState, cap);
        ci.cancel();
    }
}
