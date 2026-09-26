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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给流体液面外缘描一圈发光边框（客户端渲染专用，入口是 {@code mixin/LiquidBlockRendererMixin}）。
 *
 * 高度全部由调用方（mixin 里的原版算法）算好传进来，所以描边永远贴着真实液面。
 *
 * ## 视角剔除
 *
 * 方块是 AABB，六个面里可能同时有三个相邻的面朝向相机。此时它们共享的那个顶点周围的
 * 三条棱其实是"内部折痕"，描上去会像在方块内部画了格子。
 *
 * 规则：**一条棱由两个面共享；两面都朝向相机 → 不画这条棱**。
 *
 * 具体到 quad：
 *
 * | quad 组 | 所属棱 | 隐藏条件 |
 * | --- | --- | --- |
 * | 顶面外圈北/南/东/西 | 顶-北/南/东/西 | `camUp && camN/S/E/W` |
 * | 顶边竖片北/南/东/西 | 同上（贴液面同一条棱） | 同上 |
 * | 底边竖片北/南/东/西 | 底-北/南/东/西 | `camDown && camN/S/E/W` |
 * | 东北竖棱 | 东-北 | `camEast && camNorth` |
 * | 西北竖棱 | 西-北 | `camWest && camNorth` |
 * | 东南竖棱 | 东-南 | `camEast && camSouth` |
 * | 西南竖棱 | 西-南 | `camWest && camSouth` |
 *
 * ⚠️ 相机位置是**区块构建那一刻**读的，所以玩家移动后只有重建区块才刷新（见文末"坑"）。
 * 相机在液体内时直接整块跳过，否则所有面都变成背向、会像贴脸一层纸。
 *
 * ## 只画在流体边缘
 *
 * 先定义一条边"露在外面"：`outlineX = 邻居不是同一种流体`（源与流动算同一种；空气/实心方块都算露出来）。
 *
 * | 部位 | 什么时候画 | 效果 |
 * | --- | --- | --- |
 * | 顶面环（水平片） | 该侧露在外面、**上方不是同种流体**、**且不是下落档** | 俯视看：一整片流体只有**外轮廓**发亮 |
 * | 顶边（竖片） | 同上（与顶面环成对） | **侧视**看：液面那一圈的上沿 |
 * | 底边（竖片） | 对应方向露在外面，**且下方不是同种流体** | 侧视看：液体贴地的下沿 |
 * | 竖棱 | **真正的拐角**才算 | 直墙上不一格一根柱子 |
 *
 * 下落档（液柱）**只**有竖棱与底边。
 *
 * ## 颜色
 *
 * 默认**顶点色留白**，颜色由贴图自带。
 * 如果流体声明了 `FluidSpec.outlineTintOn`，就改用那对 tint 上色，并且自发光也跟着状态走。
 */
@OnlyIn(Dist.CLIENT)
public final class FluidOutlineRenderer {

    /** 描边宽度：贴图外圈 1px */
    private static final float EDGE = 1.0F / 16.0F;
    /** 抬出/外扩的微小距离，避免和液面自身、方块侧面 z-fight */
    private static final float LIFT = 0.002F;
    /** 满亮度顶点光 */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 顶点色：留白 */
    private static final int WHITE = 0xFFFFFFFF;

    /** 贴图 id 缓存 */
    private static final Map<String, ResourceLocation> TEXTURE_IDS = new ConcurrentHashMap<>();

    private FluidOutlineRenderer() {
    }

    /** 这一格流体要不要描边 */
    public static boolean isOutlined(FluidState fluidState) {
        FluidSpec spec = specOf(fluidState.getType());
        return spec != null && spec.getOutlined() && spec.getOutlineTexture() != null;
    }

    /** 两格是不是"同一种流体" */
    private static boolean sameFluidBody(FluidState first, FluidState second) {
        Fluid typeFirst = first.getType();
        Fluid typeSecond = second.getType();
        return typeFirst == typeSecond || typeFirst.isSame(typeSecond) || typeSecond.isSame(typeFirst);
    }

    /** 流体 → FluidSpec */
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

        // ── 颜色与自发光 ──
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

        // ── 视角剔除：读相机位置，算出六个面的朝向 ──
        // 相机在 AABB 内部：整块跳过，避免"贴脸一层纸"。
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int absX = pos.getX(), absY = pos.getY(), absZ = pos.getZ();
        float topY = Math.max(Math.max(northEast, northWest), Math.max(southEast, southWest));
        boolean camUp    = cam.y > absY + topY;
        boolean camDown  = cam.y < absY;
        boolean camNorth = cam.z < absZ;
        boolean camSouth = cam.z > absZ + 1.0;
        boolean camWest  = cam.x < absX;
        boolean camEast  = cam.x > absX + 1.0;
        if (!camUp && !camDown && !camNorth && !camSouth && !camWest && !camEast) {
            return;
        }

        // 每条棱是否可见：共享它的两个面里至少有一个背对相机。
        // 顶-北、顶-南、顶-东、顶-西：顶面外圈水平片 + 顶边竖片共用同一判断。
        boolean edgeTopNorth    = !(camUp    && camNorth);
        boolean edgeTopSouth    = !(camUp    && camSouth);
        boolean edgeTopWest     = !(camUp    && camWest);
        boolean edgeTopEast     = !(camUp    && camEast);
        // 底-北、底-南、底-东、底-西：底边竖片用。
        boolean edgeBottomNorth = !(camDown  && camNorth);
        boolean edgeBottomSouth = !(camDown  && camSouth);
        boolean edgeBottomWest  = !(camDown  && camWest);
        boolean edgeBottomEast  = !(camDown  && camEast);
        // 四条竖棱：相邻两个侧面共享。
        boolean edgePostNorthEast = !(camNorth && camEast);
        boolean edgePostNorthWest = !(camNorth && camWest);
        boolean edgePostSouthEast = !(camSouth && camEast);
        boolean edgePostSouthWest = !(camSouth && camWest);

        // ── 露在外面 = 那一侧不是同一种流体 ──
        boolean outlineNorth = !sameFluidBody(level.getBlockState(pos.north()).getFluidState(), fluidState);
        boolean outlineSouth = !sameFluidBody(level.getBlockState(pos.south()).getFluidState(), fluidState);
        boolean outlineEast  = !sameFluidBody(level.getBlockState(pos.east()).getFluidState(), fluidState);
        boolean outlineWest  = !sameFluidBody(level.getBlockState(pos.west()).getFluidState(), fluidState);

        // 顶面该不该画（上方不是同种流体、且不是下落档）
        boolean topVisible = !falling
                && !sameFluidBody(level.getBlockState(pos.above()).getFluidState(), fluidState);

        // 底面是否真露着
        boolean bottomExposed = !sameFluidBody(level.getBlockState(pos.below()).getFluidState(), fluidState);

        // 对角是不是同一种流体
        boolean diagonalNorthEast = sameFluidBody(level.getBlockState(pos.north().east()).getFluidState(), fluidState);
        boolean diagonalNorthWest = sameFluidBody(level.getBlockState(pos.north().west()).getFluidState(), fluidState);
        boolean diagonalSouthEast = sameFluidBody(level.getBlockState(pos.south().east()).getFluidState(), fluidState);
        boolean diagonalSouthWest = sameFluidBody(level.getBlockState(pos.south().west()).getFluidState(), fluidState);

        // 真正的拐角才立竖棱
        boolean postNorthEast = isCorner(outlineNorth, outlineEast, diagonalNorthEast);
        boolean postNorthWest = isCorner(outlineNorth, outlineWest, diagonalNorthWest);
        boolean postSouthEast = isCorner(outlineSouth, outlineEast, diagonalSouthEast);
        boolean postSouthWest = isCorner(outlineSouth, outlineWest, diagonalSouthWest);

        // 区块内相对坐标
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
        float cU0 = u0;
        float cU1 = uIn;
        float cV0 = v0;
        float cV1 = vIn;

        // ── 顶面外圈：四条，绕序照原版顶面 (NW → SW → SE → NE) ──
        if (topVisible && outlineNorth && edgeTopNorth) {
            emitter.quad(
                    x, y + northWest + LIFT, z, u0, v0,
                    x, y + northWest + LIFT, z + EDGE, u0, vIn,
                    x + 1, y + northEast + LIFT, z + EDGE, u1, vIn,
                    x + 1, y + northEast + LIFT, z, u1, v0,
                    0.0F, 1.0F, 0.0F);
        }
        if (topVisible && outlineSouth && edgeTopSouth) {
            emitter.quad(
                    x, y + southWest + LIFT, z + 1.0F - EDGE, u0, vLast,
                    x, y + southWest + LIFT, z + 1.0F, u0, v1,
                    x + 1, y + southEast + LIFT, z + 1.0F, u1, v1,
                    x + 1, y + southEast + LIFT, z + 1.0F - EDGE, u1, vLast,
                    0.0F, 1.0F, 0.0F);
        }
        if (topVisible && outlineWest && edgeTopWest) {
            emitter.quad(
                    x, y + northWest + LIFT, z, u0, v0,
                    x, y + southWest + LIFT, z + 1.0F, u0, v1,
                    x + EDGE, y + southWest + LIFT, z + 1.0F, uIn, v1,
                    x + EDGE, y + northWest + LIFT, z, uIn, v0,
                    0.0F, 1.0F, 0.0F);
        }
        if (topVisible && outlineEast && edgeTopEast) {
            emitter.quad(
                    x + 1.0F - EDGE, y + northEast + LIFT, z, uLast, v0,
                    x + 1.0F - EDGE, y + southEast + LIFT, z + 1.0F, uLast, v1,
                    x + 1, y + southEast + LIFT, z + 1.0F, u1, v1,
                    x + 1, y + northEast + LIFT, z, u1, v0,
                    0.0F, 1.0F, 0.0F);
        }

        // ── 顶边：沿每个露出来的侧面、在液面下方 EDGE 处画一条竖片 ──
        if (topVisible && outlineNorth && edgeTopNorth) {
            emitter.quad(
                    x, y + northWest + LIFT - EDGE, z - LIFT, u0, cV1,
                    x, y + northWest + LIFT, z - LIFT, u0, cV0,
                    x + 1, y + northEast + LIFT, z - LIFT, u1, cV0,
                    x + 1, y + northEast + LIFT - EDGE, z - LIFT, u1, cV1,
                    0.0F, 0.0F, -1.0F);
        }
        if (topVisible && outlineSouth && edgeTopSouth) {
            emitter.quad(
                    x + 1, y + southEast + LIFT - EDGE, z + 1.0F + LIFT, u1, cV1,
                    x + 1, y + southEast + LIFT, z + 1.0F + LIFT, u1, cV0,
                    x, y + southWest + LIFT, z + 1.0F + LIFT, u0, cV0,
                    x, y + southWest + LIFT - EDGE, z + 1.0F + LIFT, u0, cV1,
                    0.0F, 0.0F, 1.0F);
        }
        if (topVisible && outlineWest && edgeTopWest) {
            emitter.quad(
                    x - LIFT, y + southWest + LIFT - EDGE, z + 1.0F, u1, cV1,
                    x - LIFT, y + southWest + LIFT, z + 1.0F, u1, cV0,
                    x - LIFT, y + northWest + LIFT, z, u0, cV0,
                    x - LIFT, y + northWest + LIFT - EDGE, z, u0, cV1,
                    -1.0F, 0.0F, 0.0F);
        }
        if (topVisible && outlineEast && edgeTopEast) {
            emitter.quad(
                    x + 1.0F + LIFT, y + northEast + LIFT - EDGE, z, u0, cV1,
                    x + 1.0F + LIFT, y + northEast + LIFT, z, u0, cV0,
                    x + 1.0F + LIFT, y + southEast + LIFT, z + 1.0F, u1, cV0,
                    x + 1.0F + LIFT, y + southEast + LIFT - EDGE, z + 1.0F, u1, cV1,
                    1.0F, 0.0F, 0.0F);
        }

        // ── 底边：沿每个露出来的侧面，在贴地处画 EDGE 高的一条竖片 ──
        if (bottomExposed && outlineNorth && edgeBottomNorth) {
            emitter.quad(
                    x, y + LIFT, z - LIFT, u0, cV1,
                    x, y + EDGE, z - LIFT, u0, cV0,
                    x + 1, y + EDGE, z - LIFT, u1, cV0,
                    x + 1, y + LIFT, z - LIFT, u1, cV1,
                    0.0F, 0.0F, -1.0F);
        }
        if (bottomExposed && outlineSouth && edgeBottomSouth) {
            emitter.quad(
                    x + 1, y + LIFT, z + 1.0F + LIFT, u1, cV1,
                    x + 1, y + EDGE, z + 1.0F + LIFT, u1, cV0,
                    x, y + EDGE, z + 1.0F + LIFT, u0, cV0,
                    x, y + LIFT, z + 1.0F + LIFT, u0, cV1,
                    0.0F, 0.0F, 1.0F);
        }
        if (bottomExposed && outlineWest && edgeBottomWest) {
            emitter.quad(
                    x - LIFT, y + LIFT, z + 1.0F, u0, cV1,
                    x - LIFT, y + EDGE, z + 1.0F, u0, cV0,
                    x - LIFT, y + EDGE, z, u1, cV0,
                    x - LIFT, y + LIFT, z, u1, cV1,
                    -1.0F, 0.0F, 0.0F);
        }
        if (bottomExposed && outlineEast && edgeBottomEast) {
            emitter.quad(
                    x + 1.0F + LIFT, y + LIFT, z, u0, cV1,
                    x + 1.0F + LIFT, y + EDGE, z, u0, cV0,
                    x + 1.0F + LIFT, y + EDGE, z + 1.0F, u1, cV0,
                    x + 1.0F + LIFT, y + LIFT, z + 1.0F, u1, cV1,
                    1.0F, 0.0F, 0.0F);
        }

        // ── 四条竖棱：只在拐角，每条在相邻两个面上各一片 ──
        // 东北棱 (x=1, z=0)
        if (postNorthEast && edgePostNorthEast) {
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
        if (postNorthWest && edgePostNorthWest) {
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
        if (postSouthEast && edgePostSouthEast) {
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
        if (postSouthWest && edgePostSouthWest) {
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
     * - 只有一侧露在外面 → 直墙 ✗
     */
    private static boolean isCorner(boolean outlineFirst, boolean outlineSecond, boolean diagonalSameFluid) {
        if (outlineFirst && outlineSecond) return true;
        return !outlineFirst && !outlineSecond && !diagonalSameFluid;
    }

    /**
     * 顶点发射器：把颜色/buffer 收在这里。
     *
     * ⚠️ 不能把颜色放进 static 字段——区块重建跑在渲染线程池上，静态可变状态会被并发踩。
     */
    private static final class Emitter {

        private final VertexConsumer buffer;
        private final int red;
        private final int green;
        private final int blue;
        private final int alpha;
        private final int light;

        private Emitter(VertexConsumer buffer, int color, int light) {
            this.buffer = buffer;
            this.light = light;
            this.alpha = color >>> 24 & 0xFF;
            this.red = color >>> 16 & 0xFF;
            this.green = color >>> 8 & 0xFF;
            this.blue = color & 0xFF;
        }

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