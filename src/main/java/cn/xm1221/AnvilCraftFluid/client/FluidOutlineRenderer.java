package cn.xm1221.AnvilCraftFluid.client;

import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec;
import cn.xm1221.AnvilCraftFluid.init.AddonFluids;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
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
        BlockPos airPos = pos.relative(d);
        if (!level.getFluidState(airPos).isEmpty()) return false;
        BlockPos beyond = airPos.relative(d);
        return !level.getFluidState(beyond).isEmpty();
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
                if (i == 0 && j == 0 && !diagNW) continue;
                if (i == 2 && j == 0 && !diagNE) continue;
                if (i == 0 && j == 2 && !diagSW) continue;
                if (i == 2 && j == 2 && !diagSE) continue;

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

        boolean shellN = oN && !isConcaveCorner(level, pos, Direction.NORTH);
        boolean shellS = oS && !isConcaveCorner(level, pos, Direction.SOUTH);
        boolean shellE = oE && !isConcaveCorner(level, pos, Direction.EAST);
        boolean shellW = oW && !isConcaveCorner(level, pos, Direction.WEST);

        float hNW = cornerHeight(level, pos, Direction.NORTH, Direction.WEST, fluidState);
        float hNE = cornerHeight(level, pos, Direction.NORTH, Direction.EAST, fluidState);
        float hSW = cornerHeight(level, pos, Direction.SOUTH, Direction.WEST, fluidState);
        float hSE = cornerHeight(level, pos, Direction.SOUTH, Direction.EAST, fluidState);

        float s = SHRINK;
        float[] cx = { oW ? x + s : x, x + s, x + 1 - s, oE ? x + 1 - s : x + 1 };
        float[] cz = { oN ? z + s : z, z + s, z + 1 - s, oS ? z + 1 - s : z + 1 };

        float yBot = y;
        float yNW = oU ? y + hNW : y + 1.0F;
        float yNE = oU ? y + hNE : y + 1.0F;
        float ySW = oU ? y + hSW : y + 1.0F;
        float ySE = oU ? y + hSE : y + 1.0F;

        // ── 1. 流体本体 ──
        Emitter fluid = new Emitter(buffer, WHITE, cellLight, false);

        emitFluidHorizontalGrid(fluid, fluidSprite, x, z, cx, cz,
                yNW, yNE, ySW, ySE, yBot,
                diagNW, diagNE, diagSW, diagSE, true);
        emitFluidHorizontalGrid(fluid, fluidSprite, x, z, cx, cz,
                yNW, yNE, ySW, ySE, yBot,
                diagNW, diagNE, diagSW, diagSE, false);

        emitFluidSides(fluid, fluidSprite, x, z, cx, cz,
                yBot, yNW, yNE, ySW, ySE,
                oW, oE, oN, oS,
                diagNW, diagNE, diagSW, diagSE);

        // ── 2. 内壳：完整 quad，不做切角 ──
        float ins = INSET;
        float bx0 = oW ? x + ins : x;
        float bx1 = oE ? x + 1 - ins : x + 1;
        float bz0 = oN ? z + ins : z;
        float bz1 = oS ? z + 1 - ins : z + 1;
        float bBot = oD ? y + ins : y;
        float bTopNW = oU ? y + hNW - ins : y + 1.0F - ins;
        float bTopNE = oU ? y + hNE - ins : y + 1.0F - ins;
        float bTopSW = oU ? y + hSW - ins : y + 1.0F - ins;
        float bTopSE = oU ? y + hSE - ins : y + 1.0F - ins;

        Emitter shell = new Emitter(buffer, WHITE, FULL_BRIGHT, true);
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