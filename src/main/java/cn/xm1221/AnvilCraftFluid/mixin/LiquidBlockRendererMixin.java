package cn.xm1221.AnvilCraftFluid.mixin;

import cn.xm1221.AnvilCraftFluid.client.FluidOutlineRenderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给"挂了边框"的流体在液面上画一圈发光描边。
 *
 * ## 为什么走 mixin 而不是模型
 *
 * 原版液面**根本不是模型画的**：`blockstates/water.json` 只有一个无属性变体、`models/block/water.json`
 * 里只有一句 particle 贴图，真正的高度由 {@link LiquidBlockRenderer} 在渲染时算：
 *
 * <pre>
 * FlowingFluid#getOwnHeight(state) = getAmount(state) / 9F   // 源 = 8/9，流动 = level/9，下落档 = 8/9
 * 顶面四个角还要 calculateAverageHeight 跟水平邻居（外加对角）加权平均
 * </pre>
 *
 * 所以静态模型只能近似（而且矮侧面上贴 16×16 的边框图会被压扁）。挂在 `tesselate` 的 TAIL 上，
 * 直接复用原版那三个私有方法算出来的角点高度，描边就和液面**逐帧、逐邻居**对齐——
 * 池子内部相邻同种流体的边还会被自然剔除（只留外圈发亮）。
 *
 * ## 几何
 *
 * 顶点交给 {@link FluidOutlineRenderer}：顶面外圈 4 条（横向拉满贴图外圈 1px）、贴地处的底边 4 条、
 * 以及四条竖棱（只在流体轮廓的拐角）。全部用**满亮度**顶点光，颜色由贴图自带（上游那两张预上色的边框图）。
 *
 * 源方块 / 流动档 / 下落档（竖向液柱）一视同仁：边框只跟着"流体边缘"走，场内相邻格会被剔除掉，
 * 而"上方是同种流体"只影响顶面环（画了也看不见），竖棱照画——所以下落液柱有完整的竖边。
 *
 * ⚠️ 顶点绕序按「法线 = (v1-v0) × (v2-v1)」推的（用原版底面/顶面两组顶点验证过），别随手改顺序，
 * 否则在 `RenderType.translucent()` 的背面剔除下会整片消失。
 */
@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidBlockRendererMixin {

    /** 原版私有方法，拿来算（含邻居平均的）角点高度，保证和液面完全一致 */
    @Shadow
    private float getHeight(BlockAndTintGetter level, Fluid fluid, BlockPos pos, BlockState blockState, FluidState fluidState) {
        throw new AssertionError();
    }

    @Shadow
    private float calculateAverageHeight(BlockAndTintGetter level, Fluid fluid, float currentHeight, float height1, float height2, BlockPos pos) {
        throw new AssertionError();
    }

    @Inject(method = "tesselate", at = @At("TAIL"))
    private void anvilcraft_fluid$drawFluidOutline(
        BlockAndTintGetter level,
        BlockPos pos,
        VertexConsumer buffer,
        BlockState blockState,
        FluidState fluidState,
        CallbackInfo ci
    ) {
        if (!FluidOutlineRenderer.isOutlined(fluidState)) return;

        Fluid fluid = fluidState.getType();
        // ⚠️ 这里**不能**因为"上方是同种流体"就整格 return：
        //    原版 getHeight 在那种情况下正好返回 1.0F，而下落液柱（竖向液柱）每一格上方都是同种流体，
        //    整格跳过就等于"液柱完全没有边框"。要跳的只是看不见的顶面环，
        //    那个判断在 FluidOutlineRenderer 里按 pos.above() 单独做，竖棱照常画。
        float own = this.getHeight(level, fluid, pos, blockState, fluidState);

        float northEast;
        float northWest;
        float southEast;
        float southWest;
        if (own >= 1.0F) {
            // ⚠️ 原版 LiquidBlockRenderer#tesselate 里有这段短路：上方还是同种流体时（getHeight 正好返回
            //    1.0F），四个角**直接按满格 1.0** 算，根本不去跟邻居平均——因为那一格是"液柱内部"，
            //    侧面就是一整格高（空气邻居是 0.0，平均进去会把高度拉低）。
            //    少抄这一段的表现就是：下落液柱的竖棱每格都短一截 → "一段一段的、不连续"。
            northEast = 1.0F;
            northWest = 1.0F;
            southEast = 1.0F;
            southWest = 1.0F;
        } else {
            BlockState stateNorth = level.getBlockState(pos.north());
            BlockState stateSouth = level.getBlockState(pos.south());
            BlockState stateEast = level.getBlockState(pos.east());
            BlockState stateWest = level.getBlockState(pos.west());
            float heightNorth = this.getHeight(level, fluid, pos.north(), stateNorth, stateNorth.getFluidState());
            float heightSouth = this.getHeight(level, fluid, pos.south(), stateSouth, stateSouth.getFluidState());
            float heightEast = this.getHeight(level, fluid, pos.east(), stateEast, stateEast.getFluidState());
            float heightWest = this.getHeight(level, fluid, pos.west(), stateWest, stateWest.getFluidState());

            // 与原版顶面完全相同的四个角（东北 / 西北 / 东南 / 西南）
            northEast = this.calculateAverageHeight(level, fluid, own, heightNorth, heightEast, pos.north().east());
            northWest = this.calculateAverageHeight(level, fluid, own, heightNorth, heightWest, pos.north().west());
            southEast = this.calculateAverageHeight(level, fluid, own, heightSouth, heightEast, pos.south().east());
            southWest = this.calculateAverageHeight(level, fluid, own, heightSouth, heightWest, pos.south().west());
        }

        // 是不是下落档？原版 LiquidBlock#getFluidState：0 → 源、1~7 → 流动、>=8 → 下落。
        // ⚠️ 必须看 blockstate 的 LEVEL：`FluidState#isSource()` 对 LiquidBlock 的 0 档不可靠
        //    （之前用它判源方块就出过错），下落档则要单独识别出来交给描边器跳过顶面。
        boolean falling = blockState.hasProperty(BlockStateProperties.LEVEL)
            && blockState.getValue(BlockStateProperties.LEVEL) >= 8;

        // 有没有被红石点亮？只有导电胶体（RedstoneResinBlock）的状态里才有这一位，
        // 别的流体取不到就按 false 走——描边器也只在流体声明了 outlineTintOn 时才用它。
        boolean powered = blockState.hasProperty(BlockStateProperties.POWERED)
            && blockState.getValue(BlockStateProperties.POWERED);

        FluidOutlineRenderer.emit(
            level, pos, buffer, fluidState, falling, powered,
            northEast, northWest, southEast, southWest
        );
    }
}
