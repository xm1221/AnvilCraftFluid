package cn.xm1221.AnvilCraftFluid.client;

import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec;
import cn.xm1221.AnvilCraftFluid.init.AddonFluids;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给流体液面外缘描一圈发光边框（客户端渲染专用，入口是 {@code mixin/LiquidBlockRendererMixin}）。
 *
 * 高度全部由调用方（mixin 里的原版算法）算好传进来，所以描边永远贴着真实液面：
 * 源/流动各档的高度差、以及四个角跟邻居取平均得到的斜面都是逐帧、逐邻居对齐的。
 *
 * ## 只画在流体边缘
 *
 * 先定义一条边"露在外面"：`outlineX = 邻居不是同一种流体`（源与流动算同一种；空气/实心方块都算露出来）。
 *
 * | 部位 | 什么时候画 | 效果 |
 * | --- | --- | --- |
 * | 顶面环（水平片） | 该侧露在外面、**上方不是同种流体**、**且不是下落档** | 俯视看：一整片流体只有**外轮廓**发亮，池子内部、源方块之间都没有线 |
 * | 顶边（竖片） | 同上（与顶面环成对） | **侧视**看：液面那一圈的上沿（水平片从侧面看是零高度，所以必须有这一条） |
 * | 底边（竖片） | 对应方向露在外面，**且下方不是同种流体** | 侧视看：液体贴地的下沿；深池只在最底层出现一次 |
 * | 竖棱 | **真正的拐角**才算 | 直墙上不一格一根柱子，只在轮廓转折处出现；竖向液柱四周都是空气 → 四面竖边齐全 |
 *
 * 下落档（液柱）**只**有竖棱与底边：它的"液面"其实是被下方流体顶着的接缝，描上去会像给液柱盖个盖子。
 *
 * ## 颜色
 *
 * 默认**顶点色留白**，颜色由贴图自带（浮霜、余烬用的就是上游那两张预上色贴图）。
 *
 * 如果流体声明了 `FluidSpec.outlineTintOn`（现在是红石树脂胶体），就改用那对 tint 上色
 * （它的贴图是一张纯白图），并且**自发光也跟着状态走**：激活全亮，
 * 未激活改用该格的正常光照——放在暗处就是暗的，"激活才亮"才读得出来。
 *
 * 拐角判据（`isCorner`）：
 *
 * - 相邻两侧都露在外面 → **凸角**，画；
 * - 相邻两侧都是同种流体、但对角不是 → **凹角**（流体在这里拐进去），也画；
 * - 只有一侧露在外面 → 直墙，不画。
 *
 * 颜色：顶点色留白，所以描边就是贴图本来的样子——上游那两张**预上色**的
 * `*_metal_block_outline`（浮霜近白、余烬黄），和上游金属块那一圈边框一模一样。
 * 桶上那一圈描边是**另一个数据源**（`FluidSpec.bucketOutlineTint`，单独指定，口径与边框一致），
 * 别把两者合成一个值。
 *
 * 顶点绕序：法线 = (v1-v0) × (v2-v1)（用原版顶面/底面顶点验证过），
 * 顶面外圈取 (0,1,0)，竖棱取朝外的那一侧；在 `RenderType.translucent()` 的背面剔除下才不会消失。
 */
@OnlyIn(Dist.CLIENT)
public final class FluidOutlineRenderer {

    /** 描边宽度：贴图外圈 1px */
    private static final float EDGE = 1.0F / 16.0F;
    /** 抬出/外扩的微小距离，避免和液面自身、方块侧面 z-fight */
    private static final float LIFT = 0.002F;
    /** 满亮度顶点光：模型那边是 light_emission: 15，这里等价 */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 顶点色：留白——描边颜色完全由贴图自带（上游那两张预上色的边框图） */
    private static final int WHITE = 0xFFFFFFFF;

    /** 贴图 id 缓存（贴图本身每次重新查，纹理包重载后不会拿到旧 sprite） */
    private static final Map<String, ResourceLocation> TEXTURE_IDS = new ConcurrentHashMap<>();

    private FluidOutlineRenderer() {
    }

    /** 这一格流体要不要描边（所有档位一视同仁：源方块也描，但场内相邻的边会被剔除） */
    public static boolean isOutlined(FluidState fluidState) {
        FluidSpec spec = specOf(fluidState.getType());
        return spec != null && spec.getOutlined() && spec.getOutlineTexture() != null;
    }

    /** 两格是不是"同一种流体"（源与流动算同一种；正反都试，不依赖 isSame 的方向性） */
    private static boolean sameFluidBody(FluidState first, FluidState second) {
        Fluid typeFirst = first.getType();
        Fluid typeSecond = second.getType();
        return typeFirst == typeSecond || typeFirst.isSame(typeSecond) || typeSecond.isSame(typeFirst);
    }

    /** 流体 → 我们的 FluidSpec（按注册名反查；不是本模组的流体返回 null） */
    private static FluidSpec specOf(Fluid fluid) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        if (key == null) return null;
        String path = key.getPath();
        String name = path.startsWith("flowing_") ? path.substring("flowing_".length()) : path;
        AddonFluids.RegisteredFluid handle = AddonFluids.INSTANCE.byName(name);
        return handle == null ? null : handle.getSpec();
    }

    private static TextureAtlasSprite spriteOf(FluidSpec spec) {
        String texture = spec.getOutlineTexture();
        if (texture == null) return null;
        ResourceLocation id = TEXTURE_IDS.computeIfAbsent(texture, ResourceLocation::parse);
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id);
    }

    /**
     * 画描边。
     *
     * @param falling   这一格是不是**下落档**（blockstate 的 LEVEL ≥ 8）。下落档不画顶面，
     *                  否则液柱顶端会出现一个像盖子的横圈
     * @param powered   这一格有没有被红石点亮（方块状态的 POWERED 位）。只有声明了
     *                  {@code outlineTintOn} 的流体用得上：点亮用亮色 + 全亮，
     *                  没点亮用暗色 + 该格正常光照
     * @param northEast 东北角液面高度、其余同理（原版算法给的 0~1 高度，单位=格）
     */
    public static void emit(
        BlockAndTintGetter level,
        BlockPos pos,
        VertexConsumer buffer,
        FluidState fluidState,
        boolean falling,
        boolean powered,
        float northEast,
        float northWest,
        float southEast,
        float southWest
    ) {
        FluidSpec spec = specOf(fluidState.getType());
        if (spec == null) return;
        TextureAtlasSprite sprite = spriteOf(spec);
        if (sprite == null) return;
        // 颜色与自发光：默认顶点色留白（颜色由贴图自带）＋全亮。
        // 声明了 outlineTintOn 的流体（红石树脂胶体）改走 tint：
        //   点亮  → 亮色 + 全亮
        //   没点亮 → 暗色 + 这一格的正常光照（暗处自然就看不见）
        int color = WHITE;
        int light = FULL_BRIGHT;
        if (spec.getOutlineTintOn() != null) {
            Integer on = spec.getOutlineTintOn();
            Integer off = spec.getOutlineTintOff();
            if (powered) {
                color = on;
            } else {
                if (off != null) color = off;
                light = LightTexture.pack(
                    level.getBrightness(LightLayer.BLOCK, pos),
                    level.getBrightness(LightLayer.SKY, pos)
                );
            }
        }
        Emitter emitter = new Emitter(buffer, color, light);

        // 露在外面 = 那一侧不是同一种流体
        boolean outlineNorth = !sameFluidBody(level.getBlockState(pos.north()).getFluidState(), fluidState);
        boolean outlineSouth = !sameFluidBody(level.getBlockState(pos.south()).getFluidState(), fluidState);
        boolean outlineEast = !sameFluidBody(level.getBlockState(pos.east()).getFluidState(), fluidState);
        boolean outlineWest = !sameFluidBody(level.getBlockState(pos.west()).getFluidState(), fluidState);

        // 顶面（水平环 + 侧面上沿竖片）该不该画，两个条件：
        //   1) 上方不是同种流体（液面真的露着）。判据与"原版 getHeight 返回 1.0F"完全等价。
        //   2) 不是下落档。下落档那一格就是"液柱离开源头的那一格"：原版按 8/9 画它的液面，
        //      照着描一圈会像给液柱盖了个横盖（用户口径：很奇怪）。竖棱与底边不受影响。
        // ⚠️ 只挡顶面：下落液柱的顶面被上方流体盖着时竖棱必须照画。
        boolean topVisible = !falling
            && !sameFluidBody(level.getBlockState(pos.above()).getFluidState(), fluidState);

        // 底面是不是真的露着（下方不是同种流体）：决定"底边"要不要画。
        // 深池只有最底下一层满足，所以底边在整片流体的底沿只出现一次。
        boolean bottomExposed = !sameFluidBody(level.getBlockState(pos.below()).getFluidState(), fluidState);

        // 对角是不是同一种流体：用来区分凸角（对角是空气）和凹角（两侧都是流体但对角不是）
        boolean diagonalNorthEast = sameFluidBody(level.getBlockState(pos.north().east()).getFluidState(), fluidState);
        boolean diagonalNorthWest = sameFluidBody(level.getBlockState(pos.north().west()).getFluidState(), fluidState);
        boolean diagonalSouthEast = sameFluidBody(level.getBlockState(pos.south().east()).getFluidState(), fluidState);
        boolean diagonalSouthWest = sameFluidBody(level.getBlockState(pos.south().west()).getFluidState(), fluidState);

        // 只有真正的拐角才立竖棱：直墙上（只一侧露出来）不画
        boolean postNorthEast = isCorner(outlineNorth, outlineEast, diagonalNorthEast);
        boolean postNorthWest = isCorner(outlineNorth, outlineWest, diagonalNorthWest);
        boolean postSouthEast = isCorner(outlineSouth, outlineEast, diagonalSouthEast);
        boolean postSouthWest = isCorner(outlineSouth, outlineWest, diagonalSouthWest);

        // 区块内相对坐标（与原版 tesselate 一致）
        float x = pos.getX() & 15;
        float y = pos.getY() & 15;
        float z = pos.getZ() & 15;

        // 贴图外圈 1px 的 UV
        float u0 = sprite.getU(0.0F);
        float u1 = sprite.getU(1.0F);
        float v0 = sprite.getV(0.0F);
        float v1 = sprite.getV(1.0F);
        float uIn = sprite.getU(EDGE);
        float uLast = sprite.getU(1.0F - EDGE);
        float vIn = sprite.getV(EDGE);
        float vLast = sprite.getV(1.0F - EDGE);
        // 竖棱用贴图左上角那块像素（白图所以就是纯色），避免把 16px 的图压到很矮的侧面上
        float cU0 = u0;
        float cU1 = uIn;
        float cV0 = v0;
        float cV1 = vIn;

        // ── 顶面外圈：四条，绕序照原版顶面 (NW → SW → SE → NE)；液面被上方流体盖住就整组不画 ──
        if (topVisible && outlineNorth) {
            emitter.quad(
                x, y + northWest + LIFT, z, u0, v0,
                x, y + northWest + LIFT, z + EDGE, u0, vIn,
                x + 1, y + northEast + LIFT, z + EDGE, u1, vIn,
                x + 1, y + northEast + LIFT, z, u1, v0,
                0.0F, 1.0F, 0.0F);
        }
        if (topVisible && outlineSouth) {
            emitter.quad(
                x, y + southWest + LIFT, z + 1.0F - EDGE, u0, vLast,
                x, y + southWest + LIFT, z + 1.0F, u0, v1,
                x + 1, y + southEast + LIFT, z + 1.0F, u1, v1,
                x + 1, y + southEast + LIFT, z + 1.0F - EDGE, u1, vLast,
                0.0F, 1.0F, 0.0F);
        }
        if (topVisible && outlineWest) {
            emitter.quad(
                x, y + northWest + LIFT, z, u0, v0,
                x, y + southWest + LIFT, z + 1.0F, u0, v1,
                x + EDGE, y + southWest + LIFT, z + 1.0F, uIn, v1,
                x + EDGE, y + northWest + LIFT, z, uIn, v0,
                0.0F, 1.0F, 0.0F);
        }
        if (topVisible && outlineEast) {
            emitter.quad(
                x + 1.0F - EDGE, y + northEast + LIFT, z, uLast, v0,
                x + 1.0F - EDGE, y + southEast + LIFT, z + 1.0F, uLast, v1,
                x + 1, y + southEast + LIFT, z + 1.0F, u1, v1,
                x + 1, y + northEast + LIFT, z, u1, v0,
                0.0F, 1.0F, 0.0F);
        }

        // ── 顶边：沿每个露出来的侧面、在液面下方 EDGE 处画一条竖片（法线朝外） ──
        // 为什么不能只靠上面那圈水平环：水平片从**侧面**看是零高度、完全看不见，
        // 而侧面看过去该看到的是"液面那一圈的上沿"。和底边对称，两条竖片把侧面包住。
        // 竖片的高度取自该侧两个角的角点高度（东北/西北…），所以斜面也跟着斜。
        if (topVisible && outlineNorth) {
            emitter.quad(
                x, y + northWest + LIFT - EDGE, z - LIFT, u0, cV1,
                x, y + northWest + LIFT, z - LIFT, u0, cV0,
                x + 1, y + northEast + LIFT, z - LIFT, u1, cV0,
                x + 1, y + northEast + LIFT - EDGE, z - LIFT, u1, cV1,
                0.0F, 0.0F, -1.0F);
        }
        if (topVisible && outlineSouth) {
            emitter.quad(
                x + 1, y + southEast + LIFT - EDGE, z + 1.0F + LIFT, u1, cV1,
                x + 1, y + southEast + LIFT, z + 1.0F + LIFT, u1, cV0,
                x, y + southWest + LIFT, z + 1.0F + LIFT, u0, cV0,
                x, y + southWest + LIFT - EDGE, z + 1.0F + LIFT, u0, cV1,
                0.0F, 0.0F, 1.0F);
        }
        if (topVisible && outlineWest) {
            emitter.quad(
                x - LIFT, y + southWest + LIFT - EDGE, z + 1.0F, u1, cV1,
                x - LIFT, y + southWest + LIFT, z + 1.0F, u1, cV0,
                x - LIFT, y + northWest + LIFT, z, u0, cV0,
                x - LIFT, y + northWest + LIFT - EDGE, z, u0, cV1,
                -1.0F, 0.0F, 0.0F);
        }
        if (topVisible && outlineEast) {
            emitter.quad(
                x + 1.0F + LIFT, y + northEast + LIFT - EDGE, z, u0, cV1,
                x + 1.0F + LIFT, y + northEast + LIFT, z, u0, cV0,
                x + 1.0F + LIFT, y + southEast + LIFT, z + 1.0F, u1, cV0,
                x + 1.0F + LIFT, y + southEast + LIFT - EDGE, z + 1.0F, u1, cV1,
                1.0F, 0.0F, 0.0F);
        }

        // ── 底边：沿每个露出来的侧面，在贴地处画 EDGE 高的一条竖片（法线朝外） ──
        // 为什么不做成水平环：水平片从侧面看是零高度、完全看不见，而这一圈的意义正是"侧面看过去，
        // 液体与地面相接的那条线"。底面被下方同种流体接着时（深池内部、下落中的液柱）不画。
        if (bottomExposed && outlineNorth) {
            emitter.quad(
                x, y + LIFT, z - LIFT, u0, cV1,
                x, y + EDGE, z - LIFT, u0, cV0,
                x + 1, y + EDGE, z - LIFT, u1, cV0,
                x + 1, y + LIFT, z - LIFT, u1, cV1,
                0.0F, 0.0F, -1.0F);
        }
        if (bottomExposed && outlineSouth) {
            emitter.quad(
                x + 1, y + LIFT, z + 1.0F + LIFT, u1, cV1,
                x + 1, y + EDGE, z + 1.0F + LIFT, u1, cV0,
                x, y + EDGE, z + 1.0F + LIFT, u0, cV0,
                x, y + LIFT, z + 1.0F + LIFT, u0, cV1,
                0.0F, 0.0F, 1.0F);
        }
        if (bottomExposed && outlineWest) {
            emitter.quad(
                x - LIFT, y + LIFT, z + 1.0F, u0, cV1,
                x - LIFT, y + EDGE, z + 1.0F, u0, cV0,
                x - LIFT, y + EDGE, z, u1, cV0,
                x - LIFT, y + LIFT, z, u1, cV1,
                -1.0F, 0.0F, 0.0F);
        }
        if (bottomExposed && outlineEast) {
            emitter.quad(
                x + 1.0F + LIFT, y + LIFT, z, u0, cV1,
                x + 1.0F + LIFT, y + EDGE, z, u0, cV0,
                x + 1.0F + LIFT, y + EDGE, z + 1.0F, u1, cV0,
                x + 1.0F + LIFT, y + LIFT, z + 1.0F, u1, cV1,
                1.0F, 0.0F, 0.0F);
        }

        // ── 四条竖棱：只在拐角，每条在相邻两个面上各一片 ──
        // 东北棱 (x=1, z=0)
        if (postNorthEast) {
            emitter.quad(
                x + 1.0F + LIFT, y, z, cU0, cV1,
                x + 1.0F + LIFT, y + northEast, z, cU0, cV0,
                x + 1.0F + LIFT, y + northEast, z + EDGE, cU1, cV0,
                x + 1.0F + LIFT, y, z + EDGE, cU1, cV1,
                1.0F, 0.0F, 0.0F);
            emitter.quad(
                x + 1.0F - EDGE, y, z - LIFT, cU0, cV1,
                x + 1.0F - EDGE, y + northEast, z - LIFT, cU0, cV0,
                x + 1, y + northEast, z - LIFT, cU1, cV0,
                x + 1, y, z - LIFT, cU1, cV1,
                0.0F, 0.0F, -1.0F);
        }
        // 西北棱 (x=0, z=0)
        if (postNorthWest) {
            emitter.quad(
                x - LIFT, y, z + EDGE, cU0, cV1,
                x - LIFT, y + northWest, z + EDGE, cU0, cV0,
                x - LIFT, y + northWest, z, cU1, cV0,
                x - LIFT, y, z, cU1, cV1,
                -1.0F, 0.0F, 0.0F);
            emitter.quad(
                x, y, z - LIFT, cU0, cV1,
                x, y + northWest, z - LIFT, cU0, cV0,
                x + EDGE, y + northWest, z - LIFT, cU1, cV0,
                x + EDGE, y, z - LIFT, cU1, cV1,
                0.0F, 0.0F, -1.0F);
        }
        // 东南棱 (x=1, z=1)
        if (postSouthEast) {
            emitter.quad(
                x + 1.0F + LIFT, y, z + 1.0F - EDGE, cU0, cV1,
                x + 1.0F + LIFT, y + southEast, z + 1.0F - EDGE, cU0, cV0,
                x + 1.0F + LIFT, y + southEast, z + 1.0F, cU1, cV0,
                x + 1.0F + LIFT, y, z + 1.0F, cU1, cV1,
                1.0F, 0.0F, 0.0F);
            emitter.quad(
                x + 1, y, z + 1.0F + LIFT, cU0, cV1,
                x + 1, y + southEast, z + 1.0F + LIFT, cU0, cV0,
                x + 1.0F - EDGE, y + southEast, z + 1.0F + LIFT, cU1, cV0,
                x + 1.0F - EDGE, y, z + 1.0F + LIFT, cU1, cV1,
                0.0F, 0.0F, 1.0F);
        }
        // 西南棱 (x=0, z=1)
        if (postSouthWest) {
            emitter.quad(
                x - LIFT, y, z + 1.0F, cU0, cV1,
                x - LIFT, y + southWest, z + 1.0F, cU0, cV0,
                x - LIFT, y + southWest, z + 1.0F - EDGE, cU1, cV0,
                x - LIFT, y, z + 1.0F - EDGE, cU1, cV1,
                -1.0F, 0.0F, 0.0F);
            emitter.quad(
                x + EDGE, y, z + 1.0F + LIFT, cU0, cV1,
                x + EDGE, y + southWest, z + 1.0F + LIFT, cU0, cV0,
                x, y + southWest, z + 1.0F + LIFT, cU1, cV0,
                x, y, z + 1.0F + LIFT, cU1, cV1,
                0.0F, 0.0F, 1.0F);
        }
    }

    /**
     * 这一角要不要立竖棱。
     *
     * - 两侧都露在外面 → 凸角 ✓
     * - 两侧都被同种流体包着、但对角不是 → 凹角（流体在这里拐进去）✓
     * - 只有一侧露在外面 → 直墙 ✗（否则直墙上一格一根柱子，像栅栏）
     */
    private static boolean isCorner(boolean outlineFirst, boolean outlineSecond, boolean diagonalSameFluid) {
        if (outlineFirst && outlineSecond) return true;
        return !outlineFirst && !outlineSecond && !diagonalSameFluid;
    }

    /**
     * 顶点发射器：把颜色/buffer 收在这里，免得每个 quad 调用都多传两个参数。
     *
     * ⚠️ 不能把颜色放进 static 字段——区块重建是跑在渲染线程池上的，
     * 静态可变状态会被并发踩。
     */
    private static final class Emitter {

        private final VertexConsumer buffer;
        private final int red;
        private final int green;
        private final int blue;
        private final int alpha;
        /** 顶点光照：0xF000F0 = 全亮；也可传该格的打包光照（未激活的胶体就是这么画的） */
        private final int light;

        private Emitter(VertexConsumer buffer, int color, int light) {
            this.buffer = buffer;
            this.light = light;
            this.alpha = color >>> 24 & 0xFF;
            this.red = color >>> 16 & 0xFF;
            this.green = color >>> 8 & 0xFF;
            this.blue = color & 0xFF;
        }

        /** 一个四边形：四个顶点 + 朝外的法线（绕序必须让 法线 = (v1-v0) × (v2-v1)） */
        private void quad(
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float nx, float ny, float nz
        ) {
            vertex(x0, y0, z0, u0, v0, nx, ny, nz);
            vertex(x1, y1, z1, u1, v1, nx, ny, nz);
            vertex(x2, y2, z2, u2, v2, nx, ny, nz);
            vertex(x3, y3, z3, u3, v3, nx, ny, nz);
        }

        private void vertex(
            float x, float y, float z, float u, float v,
            float nx, float ny, float nz
        ) {
            this.buffer.addVertex(x, y, z)
                .setColor(this.red, this.green, this.blue, this.alpha)
                .setUv(u, v)
                .setLight(this.light)
                .setNormal(nx, ny, nz);
        }
    }
}
