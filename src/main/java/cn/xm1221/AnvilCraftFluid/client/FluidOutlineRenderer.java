package cn.xm1221.AnvilCraftFluid.client;

import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec;
import cn.xm1221.AnvilCraftFluid.init.AddonFluids;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缩小流体块 + 内壳（法线朝内）作为描边。
 *
 * ## 关键：顶面和侧面共边
 *
 * 顶面水平缩了 SHRINK，缩后的边界在 cx[i]/cz[j]。为了让顶面和侧面**共边**，
 * 侧面的每个顶点也必须用和顶面**同一套坐标 + 同一套高度插值**：
 *
 * - 顶面顶点：位置 (cx[i], cz[j])，高度 = bilinear(yNW, yNE, ySW, ySE, cx[i]-x, cz[j]-z)
 * - 侧面顶点：位置 (cx[i], cz[j])，高度 = 同一个 bilinear
 *
 * 之前侧面直接用四角原始高度 yNW/yNE/ySW/ySE，和顶面的 bilinear 值不一致，
 * 液面倾斜时顶面和侧面之间会出现一条缝。
 *
 * ## 切角（仅作用于流体本体）
 *
 * 斜对角是空气、两个正邻居是同种流体时，角落凸出 s×s 小方块，切掉（直角切口）。
 * 顶面/底面用 3×3 网格，4 角块按对角可见性决定画不画。
 * 侧面拆 3 段 + 切角露出面。
 *
 * ## 壳（不做切角）
 *
 * 每个面都是**完整 quad**：要么整个提交，要么整个不提交。侧面只在 oX && !凹角
 * 时提交。边界对齐方块边界，缩 INSET。
 *
 * ## 缩量规则
 *
 * | 方向 | oX=false | oX=true |
 * | --- | --- | --- |
 * | 水平 | 到方块边界 | 缩 SHRINK |
 * | 上 | 液面 y+1 | 液面 y+hXX |
 * | 下 | 底 y | 底 y |
 */
@OnlyIn(Dist.CLIENT)
public final class FluidOutlineRenderer {

    private static final float SHRINK = 2.0F / 16.0F;
    private static final float INSET = 0.005F;
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Map<String, ResourceLocation> TEXTURE_IDS = new ConcurrentHashMap<>();

    private FluidOutlineRenderer() {
    }

    public static boolean isOutlined(FluidState fluidState) {
        FluidSpec spec = specOf(fluidState.getType());
        return spec != null && spec.getOutlined() && spec.getOutlineTexture() != null;
    }

    private static boolean sameFluidBody(FluidState first, FluidState second) {
        Fluid a = first.getType();
        Fluid b = second.getType();
        return a == b || a.isSame(b) || b.isSame(a);
    }

    private static FluidSpec specOf(Fluid fluid) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        if (key == null) return null;
        String path = key.getPath();
        String name = path.startsWith("flowing_") ? path.substring("flowing_".length()) : path;
        AddonFluids.RegisteredFluid handle = AddonFluids.INSTANCE.byName(name);
        return handle == null ? null : handle.getSpec();
    }

    private static TextureAtlasSprite shellSpriteOf(FluidSpec spec) {
        String texture = spec.getOutlineTexture();
        if (texture == null) return null;
        ResourceLocation id = TEXTURE_IDS.computeIfAbsent(texture, ResourceLocation::parse);
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id);
    }

    private static TextureAtlasSprite fluidSpriteOf(
            BlockAndTintGetter level, BlockPos pos, FluidState fluidState
    ) {
        FluidType fluidType = fluidState.getFluidType();
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluidType);
        ResourceLocation still = ext.getStillTexture(fluidState, level, pos);
        if (still == null) {
            return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(ResourceLocation.withDefaultNamespace("missingno"));
        }
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
    }

    private static boolean isConcaveCorner(BlockAndTintGetter level, BlockPos pos, Direction d) {
        BlockPos mid = pos.relative(d);
        // 中间那格必须是真的空气才算凹角。
        // "流体 方块 流体"这种排布：中间是实心方块时这一侧的壳面照样能看见，剔掉就漏了。
        if (!level.getBlockState(mid).isAir()) return false;
        return !level.getFluidState(mid.relative(d)).isEmpty();
    }

    private static float cornerHeight(
            BlockAndTintGetter level, BlockPos pos, Direction d1, Direction d2, FluidState self
    ) {
        float selfH = self.getOwnHeight();
        if (sameFluidBody(level.getBlockState(pos.above()).getFluidState(), self)) {
            selfH = 1.0F;
        }
        BlockPos p1 = pos.relative(d1);
        BlockPos p2 = pos.relative(d2);
        BlockPos p12 = p1.relative(d2);
        float h1 = sampleHeight(level, p1, self, selfH);
        float h2 = sampleHeight(level, p2, self, selfH);
        float h12 = sampleHeight(level, p12, self, selfH);
        return (selfH + h1 + h2 + h12) / 4.0F;
    }

    private static float sampleHeight(BlockAndTintGetter level, BlockPos pos, FluidState self, float fallback) {
        FluidState s = level.getFluidState(pos);
        if (!sameFluidBody(s, self)) return fallback;
        if (sameFluidBody(level.getBlockState(pos.above()).getFluidState(), self)) return 1.0F;
        return s.getOwnHeight();
    }

    /**
     * 双线性插值。四个角 (0,0)=NW, (1,0)=NE, (0,1)=SW, (1,1)=SE。
     * u、v 是相对坐标（0..1）。
     */
    private static float bilinear(float yNW, float yNE, float ySW, float ySE, float u, float v) {
        return yNW * (1 - u) * (1 - v)
                + yNE * u * (1 - v)
                + ySW * (1 - u) * v
                + ySE * u * v;
    }

    /**
     * 流体本体的顶面/底面 3×3 网格。角块按对角可见性决定画不画。
     * 顶点高度用 bilinear，和侧面共用同一套高度。
     */
    private static void emitFluidHorizontalGrid(
            Emitter emitter, TextureAtlasSprite sprite,
            float x, float z,
            float[] cx, float[] cz,
            float yNW, float yNE, float ySW, float ySE,
            float yBot,
            boolean diagNW, boolean diagNE, boolean diagSW, boolean diagSE,
            boolean up
    ) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                // 角块一律照画：以前按对角可见性跳过，两个正交邻居都是同种流体时
                // 顶面就会缺一个 s×s 的方块（下面是个通到底的洞）。现在不切了。
                float xa = cx[i], xb = cx[i + 1];
                float za = cz[j], zb = cz[j + 1];
                if (xb <= xa || zb <= za) continue;

                float ua = sprite.getU(xa - x);
                float ub = sprite.getU(xb - x);
                float va = sprite.getV(za - z);
                float vb = sprite.getV(zb - z);

                if (up) {
                    float yAA = bilinear(yNW, yNE, ySW, ySE, xa - x, za - z);
                    float yBA = bilinear(yNW, yNE, ySW, ySE, xb - x, za - z);
                    float yAB = bilinear(yNW, yNE, ySW, ySE, xa - x, zb - z);
                    float yBB = bilinear(yNW, yNE, ySW, ySE, xb - x, zb - z);
                    emitter.quad(
                            xa, yAA, za, ua, va,
                            xa, yAB, zb, ua, vb,
                            xb, yBB, zb, ub, vb,
                            xb, yBA, za, ub, va,
                            0, 1, 0);
                } else {
                    emitter.quad(
                            xa, yBot, za, ua, va,
                            xb, yBot, za, ub, va,
                            xb, yBot, zb, ub, vb,
                            xa, yBot, zb, ua, vb,
                            0, -1, 0);
                }
            }
        }
    }

    /**
     * 提交一段侧面 quad。两端高度由调用者传入（用 bilinear 算好的）。
     */
    private static void sideQuad(
            Emitter e, TextureAtlasSprite tex,
            float xa, float za, float xb, float zb,
            float yTopA, float yTopB, float yBot,
            float nx, float ny, float nz
    ) {
        float u0 = tex.getU(0.0F), u1 = tex.getU(1.0F);
        float v0 = tex.getV(0.0F), v1 = tex.getV(1.0F);

        if (nz == -1.0F) {
            e.quad(
                    xa, yTopA, za, u0, v0,
                    xb, yTopB, zb, u1, v0,
                    xb, yBot, zb, u1, v1,
                    xa, yBot, za, u0, v1,
                    nx, ny, nz);
        } else {
            e.quad(
                    xa, yTopA, za, u0, v0,
                    xa, yBot, za, u0, v1,
                    xb, yBot, zb, u1, v1,
                    xb, yTopB, zb, u1, v0,
                    nx, ny, nz);
        }
    }

    /**
     * 提交一段流体侧面：两端高度用 bilinear 算好，和顶面边界共边。
     */
    private static void fluidSideQuad(
            Emitter e, TextureAtlasSprite tex,
            float x, float z, float yBot,
            float yNW, float yNE, float ySW, float ySE,
            float xa, float za, float xb, float zb,
            float nx, float ny, float nz
    ) {
        float hA = bilinear(yNW, yNE, ySW, ySE, xa - x, za - z);
        float hB = bilinear(yNW, yNE, ySW, ySE, xb - x, zb - z);
        sideQuad(e, tex, xa, za, xb, zb, hA, hB, yBot, nx, ny, nz);
    }

    /**
     * 流体本体侧面：3 段 + 切角露出面。
     * 每个顶点的高度都用 bilinear，和顶面网格共边。
     */
    private static void emitFluidSides(
            Emitter e, TextureAtlasSprite tex,
            float x, float z, float[] cx, float[] cz,
            float yBot, float yNW, float yNE, float ySW, float ySE,
            boolean oW, boolean oE, boolean oN, boolean oS,
            boolean diagNW, boolean diagNE, boolean diagSW, boolean diagSE
    ) {
        // ── 北面（-z） ──
        if (oN) {
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[0], cx[3], cz[0], 0, 0, -1);
        } else {
            if (!oW && diagNW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[0], cx[1], cz[0], 0, 0, -1);
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[1], cz[0], cx[2], cz[0], 0, 0, -1);
            if (!oE && diagNE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[2], cz[0], cx[3], cz[0], 0, 0, -1);
            if (!oW && !diagNW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[1], cx[1], cz[1], 0, 0, -1);
            if (!oE && !diagNE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[2], cz[1], cx[3], cz[1], 0, 0, -1);
        }
        // ── 南面（+z） ──
        if (oS) {
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[3], cx[3], cz[3], 0, 0, 1);
        } else {
            if (!oW && diagSW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[3], cx[1], cz[3], 0, 0, 1);
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[1], cz[3], cx[2], cz[3], 0, 0, 1);
            if (!oE && diagSE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[2], cz[3], cx[3], cz[3], 0, 0, 1);
            if (!oW && !diagSW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[2], cx[1], cz[2], 0, 0, 1);
            if (!oE && !diagSE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[2], cz[2], cx[3], cz[2], 0, 0, 1);
        }
        // ── 西面（-x） ──
        if (oW) {
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[0], cx[0], cz[3], -1, 0, 0);
        } else {
            if (!oN && diagNW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[0], cx[0], cz[1], -1, 0, 0);
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[1], cx[0], cz[2], -1, 0, 0);
            if (!oS && diagSW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[0], cz[2], cx[0], cz[3], -1, 0, 0);
            if (!oN && !diagNW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[1], cz[0], cx[1], cz[1], -1, 0, 0);
            if (!oS && !diagSW) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[1], cz[2], cx[1], cz[3], -1, 0, 0);
        }
        // ── 东面（+x） ──
        if (oE) {
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[3], cz[3], cx[3], cz[0], 1, 0, 0);
        } else {
            if (!oS && diagSE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[3], cz[3], cx[3], cz[2], 1, 0, 0);
            fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[3], cz[2], cx[3], cz[1], 1, 0, 0);
            if (!oN && diagNE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[3], cz[1], cx[3], cz[0], 1, 0, 0);
            if (!oS && !diagSE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[2], cz[3], cx[2], cz[2], 1, 0, 0);
            if (!oN && !diagNE) fluidSideQuad(e, tex, x, z, yBot, yNW, yNE, ySW, ySE,
                    cx[2], cz[1], cx[2], cz[0], 1, 0, 0);
        }
    }

    public static void emit(
            BlockAndTintGetter level,
            BlockPos pos,
            VertexConsumer buffer,
            FluidState fluidState
    ) {
        emit(level, pos, buffer, fluidState, null);
    }

    /**
     * @param cap 原版这一格画出来的顶点（mixin 捕获的）。液面高度和体块形状都由它提供，
     *            为 null 或这一格原版什么都没画时才退回本地估算（只作兜底）。
     */
    public static void emit(
            BlockAndTintGetter level,
            BlockPos pos,
            VertexConsumer buffer,
            FluidState fluidState,
            FluidVertexCapture cap
    ) {
        FluidSpec spec = specOf(fluidState.getType());
        if (spec == null || !spec.getOutlined() || spec.getOutlineTexture() == null) return;

        TextureAtlasSprite shellSprite = shellSpriteOf(spec);
        if (shellSprite == null) return;
        TextureAtlasSprite fluidSprite = fluidSpriteOf(level, pos, fluidState);

        int cellLight = LightTexture.pack(
                level.getBrightness(LightLayer.BLOCK, pos),
                level.getBrightness(LightLayer.SKY, pos)
        );

        float x = pos.getX() & 15;
        float y = pos.getY() & 15;
        float z = pos.getZ() & 15;

        if (fluidState.getOwnHeight() <= 0.0F) return;

        boolean oN = !sameFluidBody(level.getBlockState(pos.north()).getFluidState(), fluidState);
        boolean oS = !sameFluidBody(level.getBlockState(pos.south()).getFluidState(), fluidState);
        boolean oE = !sameFluidBody(level.getBlockState(pos.east()).getFluidState(), fluidState);
        boolean oW = !sameFluidBody(level.getBlockState(pos.west()).getFluidState(), fluidState);
        boolean oU = !sameFluidBody(level.getBlockState(pos.above()).getFluidState(), fluidState);
        boolean oD = !sameFluidBody(level.getBlockState(pos.below()).getFluidState(), fluidState);

        boolean diagNW = sameFluidBody(level.getBlockState(pos.north().west()).getFluidState(), fluidState);
        boolean diagNE = sameFluidBody(level.getBlockState(pos.north().east()).getFluidState(), fluidState);
        boolean diagSW = sameFluidBody(level.getBlockState(pos.south().west()).getFluidState(), fluidState);
        boolean diagSE = sameFluidBody(level.getBlockState(pos.south().east()).getFluidState(), fluidState);

        // 斜角相邻的两个流体要能在角上接上：只要有一个斜对角是同种流体，
        // 这一格**面向空气**的那几侧就回到原本的尺寸（不再内缩）。
        // 代价是那几侧的描边边带会消失——这是用户拍板的取舍。
        boolean diagAny = diagNW || diagNE || diagSW || diagSE;
        boolean bareN = diagAny && level.getBlockState(pos.north()).isAir();
        boolean bareS = diagAny && level.getBlockState(pos.south()).isAir();
        boolean bareE = diagAny && level.getBlockState(pos.east()).isAir();
        boolean bareW = diagAny && level.getBlockState(pos.west()).isAir();
        boolean insetN = oN && !bareN;
        boolean insetS = oS && !bareS;
        boolean insetE = oE && !bareE;
        boolean insetW = oW && !bareW;

        boolean shellN = oN && !isConcaveCorner(level, pos, Direction.NORTH);
        boolean shellS = oS && !isConcaveCorner(level, pos, Direction.SOUTH);
        boolean shellE = oE && !isConcaveCorner(level, pos, Direction.EAST);
        boolean shellW = oW && !isConcaveCorner(level, pos, Direction.WEST);

        // 四个角的液面高度：直接从**原版自己画出来的顶点**里读（每个角取该角最高的顶点）。
        // 这就是"液面由原生方法渲染"：高度是原版的产物，不是我们算的。
        float[] vh = cap != null && cap.vertices() > 0 ? captureCornerHeights(cap, x, y, z) : null;
        float hNW = vh != null && vh[0] >= 0.0F ? vh[0] : cornerHeight(level, pos, Direction.NORTH, Direction.WEST, fluidState);
        float hNE = vh != null && vh[1] >= 0.0F ? vh[1] : cornerHeight(level, pos, Direction.NORTH, Direction.EAST, fluidState);
        float hSW = vh != null && vh[2] >= 0.0F ? vh[2] : cornerHeight(level, pos, Direction.SOUTH, Direction.WEST, fluidState);
        float hSE = vh != null && vh[3] >= 0.0F ? vh[3] : cornerHeight(level, pos, Direction.SOUTH, Direction.EAST, fluidState);

        float s = SHRINK;
        float[] cx = { insetW ? x + s : x, x + s, x + 1 - s, insetE ? x + 1 - s : x + 1 };
        float[] cz = { insetN ? z + s : z, z + s, z + 1 - s, insetS ? z + 1 - s : z + 1 };

        float yBot = y;
        float yNW = oU ? y + hNW : y + 1.0F;
        float yNE = oU ? y + hNE : y + 1.0F;
        float ySW = oU ? y + hSW : y + 1.0F;
        float ySE = oU ? y + hSE : y + 1.0F;

       
        float[] ledgeH = { -1.0F, -1.0F, -1.0F, -1.0F };   // W, E, N, S
        boolean hasLedge = false;
        if (!oU) {
            if (!oW) { float h = sampleHeight(level, pos.west(), fluidState, 1.0F); if (h < 1.0F - 1.0E-4F) { ledgeH[0] = y + h; hasLedge = true; } }
            if (!oE) { float h = sampleHeight(level, pos.east(), fluidState, 1.0F); if (h < 1.0F - 1.0E-4F) { ledgeH[1] = y + h; hasLedge = true; } }
            if (!oN) { float h = sampleHeight(level, pos.north(), fluidState, 1.0F); if (h < 1.0F - 1.0E-4F) { ledgeH[2] = y + h; hasLedge = true; } }
            if (!oS) { float h = sampleHeight(level, pos.south(), fluidState, 1.0F); if (h < 1.0F - 1.0E-4F) { ledgeH[3] = y + h; hasLedge = true; } }
        }
        // 顶面用"切掉带"的 footprint；侧面也用同一份，好让 ③ 在切面上补出墙
        float[] cxIn = cx;
        float[] czIn = cz;
        if (hasLedge) {
            cxIn = cx.clone();
            czIn = cz.clone();
            if (ledgeH[0] >= 0.0F) cxIn[0] = cxIn[1];
            if (ledgeH[1] >= 0.0F) cxIn[3] = cxIn[2];
            if (ledgeH[2] >= 0.0F) czIn[0] = czIn[1];
            if (ledgeH[3] >= 0.0F) czIn[3] = czIn[2];
        }

        // ── 1. 流体本体 ──
        // 绝大多数格子（四个对角都是同种流体、也没有竖直 L 角）根本没有切角要做：
        // 直接把原版画出来的顶点水平内缩后原样重放 —— 形状、面、光照全是原版的。
        boolean vanillaBody = cap != null && cap.vertices() > 0 && !hasLedge
                && diagNW && diagNE && diagSW && diagSE;
        if (vanillaBody) {
            emitVanillaBody(buffer, cap, x, z, insetW, insetE, insetN, insetS);
        }

        // 有切角（对角是别的流体/空气）或有竖直 L 角的格子：走自己那套"切掉 + 补面"
        if (!vanillaBody) {
        Emitter fluid = new Emitter(buffer, WHITE, cellLight, false);

        emitFluidHorizontalGrid(fluid, fluidSprite, x, z, cxIn, czIn,
                yNW, yNE, ySW, ySE, yBot,
                diagNW, diagNE, diagSW, diagSE, true);
        // 底面不切：切的是"顶面朝那一侧的那条带"，底面仍按整格画
        emitFluidHorizontalGrid(fluid, fluidSprite, x, z, cx, cz,
                yNW, yNE, ySW, ySE, yBot,
                diagNW, diagNE, diagSW, diagSE, false);

        emitFluidSides(fluid, fluidSprite, x, z, cxIn, czIn,
                yBot, yNW, yNE, ySW, ySE,
                ledgeH[0] >= 0.0F || oW,
                ledgeH[1] >= 0.0F || oE,
                ledgeH[2] >= 0.0F || oN,
                ledgeH[3] >= 0.0F || oS,
                diagNW, diagNE, diagSW, diagSE);

        // 被切掉的那条带：顶面降到对方液面高度，并补上它自己朝外的两条墙
        for (int d = 0; d < 4; d++) {
            float lh = ledgeH[d];
            if (lh < 0.0F) continue;

            float bxA, bxB, bzA, bzB;
            if (d == 0) {          // 西侧的带
                bxA = cx[0]; bxB = cx[1]; bzA = czIn[0]; bzB = czIn[3];
            } else if (d == 1) {   // 东侧的带
                bxA = cx[2]; bxB = cx[3]; bzA = czIn[0]; bzB = czIn[3];
            } else if (d == 2) {   // 北侧的带
                bxA = cxIn[0]; bxB = cxIn[3]; bzA = cz[0]; bzB = cz[1];
            } else {               // 南侧的带
                bxA = cxIn[0]; bxB = cxIn[3]; bzA = cz[2]; bzB = cz[3];
            }
            if (bxB <= bxA || bzB <= bzA) continue;

            emitFlatTop(fluid, fluidSprite, x, z, bxA, bxB, bzA, bzB, lh);

            // 带自己朝外的两条墙（垂直于"带的方向"的那两侧；只在真正露在外面的方向画）
            if (d < 2) {
                if (oN) sideQuad(fluid, fluidSprite, bxA, bzA, bxB, bzA, lh, lh, yBot, 0, 0, -1);
                if (oS) sideQuad(fluid, fluidSprite, bxA, bzB, bxB, bzB, lh, lh, yBot, 0, 0, 1);
            } else {
                if (oW) sideQuad(fluid, fluidSprite, bxA, bzA, bxA, bzB, lh, lh, yBot, -1, 0, 0);
                if (oE) sideQuad(fluid, fluidSprite, bxB, bzB, bxB, bzA, lh, lh, yBot, 1, 0, 0);
            }

            // 竖直 L 角只切"角上那一小块"：把这条带里不在角上的部分按原高度补回来
            emitBandRestore(fluid, fluidSprite, x, z, d, bxA, bxB, bzA, bzB, yBot,
                    yNW, yNE, ySW, ySE,
                    d < 2 ? insetN : insetW,
                    d < 2 ? insetS : insetE);
        }
        }

        // ── 2. 内壳：完整 quad，不做切角 ──
        // 邻居那格也是"带壳流体"（它在这一侧同样画壳面）→ 两面贴到格子边界上对接，中间不留缝；
        // 邻居是空气/实心方块/普通流体 → 照旧内缩 INSET，免得和它的表面重合打架。
        // 上下两面（顶盖/底盖）一律留 INSET。
        float ins = INSET;
        float bx0 = oW ? (shelledNeighbour(level, pos, Direction.WEST) ? x : x + ins) : x;
        float bx1 = oE ? (shelledNeighbour(level, pos, Direction.EAST) ? x + 1 : x + 1 - ins) : x + 1;
        float bz0 = oN ? (shelledNeighbour(level, pos, Direction.NORTH) ? z : z + ins) : z;
        float bz1 = oS ? (shelledNeighbour(level, pos, Direction.SOUTH) ? z + 1 : z + 1 - ins) : z + 1;
        float bBot = oD ? y + ins : y;
        float bTopNW = oU ? y + hNW - ins : y + 1.0F - ins;
        float bTopNE = oU ? y + hNE - ins : y + 1.0F - ins;
        float bTopSW = oU ? y + hSW - ins : y + 1.0F - ins;
        float bTopSE = oU ? y + hSE - ins : y + 1.0F - ins;

        // 内壳的颜色和自发光**可以跟着"有没有被点亮"走**（红石树脂胶体就是这么用的）：
        //   · 声明了 outlineTintOn 的流体 → 点亮 = 亮色 + 全亮；未点亮 = outlineTintOff + 这一格的正常光照
        //     （暗处自然就看不见，符合"未激活不亮"）
        //   · 其余流体 → 贴图自带颜色（WHITE = 不染色）+ 全亮，行为和以前一样
        // POWERED 直接按**原版属性**读：不依赖具体方块类，任何带这个属性的流体都能用。
        int shellColor = WHITE;
        int shellLight = FULL_BRIGHT;
        if (spec.getOutlineTintOn() != null) {
            BlockState state = level.getBlockState(pos);
            boolean powered = state.hasProperty(BlockStateProperties.POWERED)
                    && state.getValue(BlockStateProperties.POWERED);
            if (powered) {
                shellColor = spec.getOutlineTintOn();
            } else {
                Integer off = spec.getOutlineTintOff();
                if (off != null) shellColor = off;
                shellLight = cellLight;
            }
        }

        Emitter shell = new Emitter(buffer, shellColor, shellLight, true);
        float su0 = shellSprite.getU(0.0F), su1 = shellSprite.getU(1.0F);
        float sv0 = shellSprite.getV(0.0F), sv1 = shellSprite.getV(1.0F);

        if (oU) {
            shell.quad(
                    bx0, bTopNW, bz0, su0, sv0,
                    bx0, bTopSW, bz1, su0, sv1,
                    bx1, bTopSE, bz1, su1, sv1,
                    bx1, bTopNE, bz0, su1, sv0,
                    0, 1, 0);
        }
        if (oD) {
            shell.quad(
                    bx0, bBot, bz0, su0, sv0,
                    bx1, bBot, bz0, su1, sv0,
                    bx1, bBot, bz1, su1, sv1,
                    bx0, bBot, bz1, su0, sv1,
                    0, -1, 0);
        }
        if (shellN) {
            shell.quad(
                    bx0, bTopNW, bz0, su0, sv0,
                    bx1, bTopNE, bz0, su1, sv0,
                    bx1, bBot, bz0, su1, sv1,
                    bx0, bBot, bz0, su0, sv1,
                    0, 0, -1);
        }
        if (shellS) {
            shell.quad(
                    bx0, bTopSW, bz1, su0, sv0,
                    bx0, bBot, bz1, su0, sv1,
                    bx1, bBot, bz1, su1, sv1,
                    bx1, bTopSE, bz1, su1, sv0,
                    0, 0, 1);
        }
        if (shellW) {
            shell.quad(
                    bx0, bTopNW, bz0, su0, sv0,
                    bx0, bBot, bz0, su0, sv1,
                    bx0, bBot, bz1, su1, sv1,
                    bx0, bTopSW, bz1, su1, sv0,
                    -1, 0, 0);
        }
        if (shellE) {
            shell.quad(
                    bx1, bTopSE, bz1, su0, sv0,
                    bx1, bBot, bz1, su0, sv1,
                    bx1, bBot, bz0, su1, sv1,
                    bx1, bTopNE, bz0, su1, sv0,
                    1, 0, 0);
        }
    }

    /**
     * 邻居那格在与我相邻的这一侧会不会也画内壳（= 它也是"带壳流体"）。
     * 会画 → 两面在格子边界上对接，无缝；不会画 → 我们这面退回内缩，免得和它的表面重合。
     */
    private static boolean shelledNeighbour(BlockAndTintGetter level, BlockPos pos, Direction d) {
        BlockPos np = pos.relative(d);
        FluidSpec spec = specOf(level.getBlockState(np).getFluidState().getType());
        if (spec == null || !spec.getOutlined() || spec.getOutlineTexture() == null) return false;
        // 它朝我这一侧也得真的画壳面（中间隔的那格是我这格、有流体，不会算凹角）
        return !isConcaveCorner(level, np, d.getOpposite());
    }

    /** 从原版顶点里读这格四个角的液面高度（相对格底）：每个角取落在该角上最高的那个顶点 */
    private static float[] captureCornerHeights(FluidVertexCapture cap, float x, float y, float z) {
        float[] h = { -1.0F, -1.0F, -1.0F, -1.0F };   // NW, NE, SW, SE
        int n = cap.vertices();
        for (int i = 0; i < n; i++) {
            int corner = (cap.get(i, 0) - x > 0.5F ? 1 : 0) + (cap.get(i, 2) - z > 0.5F ? 2 : 0);
            float ly = cap.get(i, 1) - y;
            if (ly > h[corner]) h[corner] = ly;
        }
        return h;
    }

    /**
     * 把原版画出来的这一格顶点原样重放出去，只把坐标按"该侧要内缩则水平内缩 s"重映射：
     * 相邻方向有同种流体、或斜角相邻而这一侧是空气 → 那个方向回到原本的尺寸（两格的面正好接上），
     * 其余暴露方向 → 内缩 s，与壳之间留出一圈描边。y 不动，液面就是原版的那个高度。
     */
    private static void emitVanillaBody(
            VertexConsumer out, FluidVertexCapture cap,
            float x, float z, boolean insetW, boolean insetE, boolean insetN, boolean insetS
    ) {
        int n = cap.vertices();
        for (int i = 0; i < n; i++) {
            float lx = cap.get(i, 0) - x;
            float lz = cap.get(i, 2) - z;
            if (lx <= 1.0E-4F) lx = insetW ? SHRINK : 0.0F;
            else if (lx >= 1.0F - 1.0E-4F) lx = insetE ? 1.0F - SHRINK : 1.0F;
            if (lz <= 1.0E-4F) lz = insetN ? SHRINK : 0.0F;
            else if (lz >= 1.0F - 1.0E-4F) lz = insetS ? 1.0F - SHRINK : 1.0F;
            out.addVertex(x + lx, cap.get(i, 1), z + lz)
                    .setColor(255, 255, 255, 255)
                    .setUv(cap.get(i, 3), cap.get(i, 4))
                    .setUv2((int) cap.get(i, 5), (int) cap.get(i, 6))
                    .setNormal(cap.get(i, 7), cap.get(i, 8), cap.get(i, 9));
        }
    }

    /** 一条水平面：竖直 L 角里被切下来的那条 s 宽带，补在相邻块的液面高度上 */
    private static void emitFlatTop(
            Emitter e, TextureAtlasSprite sprite, float x, float z,
            float xa, float xb, float za, float zb, float yTop
    ) {
        float ua = sprite.getU(xa - x);
        float ub = sprite.getU(xb - x);
        float va = sprite.getV(za - z);
        float vb = sprite.getV(zb - z);
        e.quad(
                xa, yTop, za, ua, va,
                xa, yTop, zb, ua, vb,
                xb, yTop, zb, ub, vb,
                xb, yTop, za, ub, va,
                0, 1, 0);
    }

    /**
     * 竖直 L 角只切"角上那一小块"，不是整条边带：
     * 把这条 s 宽的带里**不在角上**的部分按原高度（双线性）补回来，
     * 于是被切下去的只剩每个角上 s×s 的那一块，高度由相邻块的液面决定（ledgeH）。
     * 补回来的这块四面墙都提交，露在外面的那两面正好把角上那一块的台阶补上，另外两面藏在体内。
     */
    private static void emitBandRestore(
            Emitter e, TextureAtlasSprite tex, float x, float z, int d,
            float bxA, float bxB, float bzA, float bzB, float yBot,
            float yNW, float yNE, float ySW, float ySE,
            boolean insetAtLowEnd, boolean insetAtHighEnd
    ) {
        boolean alongZ = d < 2;
        float lo = alongZ ? bzA : bxA;
        float hi = alongZ ? bzB : bxB;
        if (insetAtLowEnd) lo += SHRINK;
        if (insetAtHighEnd) hi -= SHRINK;
        if (hi - lo <= 1.0E-4F) return;

        float x0 = alongZ ? bxA : lo;
        float x1 = alongZ ? bxB : hi;
        float z0 = alongZ ? lo : bzA;
        float z1 = alongZ ? hi : bzB;

        float t00 = bilinear(yNW, yNE, ySW, ySE, x0 - x, z0 - z);
        float t10 = bilinear(yNW, yNE, ySW, ySE, x1 - x, z0 - z);
        float t11 = bilinear(yNW, yNE, ySW, ySE, x1 - x, z1 - z);
        float t01 = bilinear(yNW, yNE, ySW, ySE, x0 - x, z1 - z);

        float ua = tex.getU(x0 - x);
        float ub = tex.getU(x1 - x);
        float va = tex.getV(z0 - z);
        float vb = tex.getV(z1 - z);
        e.quad(
                x0, t00, z0, ua, va,
                x0, t01, z1, ua, vb,
                x1, t11, z1, ub, vb,
                x1, t10, z0, ub, va,
                0, 1, 0);

        sideQuad(e, tex, x0, z0, x0, z1, t00, t01, yBot, -1, 0, 0);
        sideQuad(e, tex, x1, z1, x1, z0, t11, t10, yBot, 1, 0, 0);
        sideQuad(e, tex, x0, z0, x1, z0, t00, t10, yBot, 0, 0, -1);
        sideQuad(e, tex, x0, z1, x1, z1, t01, t11, yBot, 0, 0, 1);
    }

    private static final class Emitter {
        private final VertexConsumer buffer;
        private final int red, green, blue, alpha;
        private final int light;
        private final boolean invert;

        private Emitter(VertexConsumer buffer, int color, int light, boolean invert) {
            this.buffer = buffer;
            this.light = light;
            this.invert = invert;
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
            if (invert) {
                vertex(x3, y3, z3, u3, v3, -nx, -ny, -nz);
                vertex(x2, y2, z2, u2, v2, -nx, -ny, -nz);
                vertex(x1, y1, z1, u1, v1, -nx, -ny, -nz);
                vertex(x0, y0, z0, u0, v0, -nx, -ny, -nz);
            } else {
                vertex(x0, y0, z0, u0, v0, nx, ny, nz);
                vertex(x1, y1, z1, u1, v1, nx, ny, nz);
                vertex(x2, y2, z2, u2, v2, nx, ny, nz);
                vertex(x3, y3, z3, u3, v3, nx, ny, nz);
            }
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