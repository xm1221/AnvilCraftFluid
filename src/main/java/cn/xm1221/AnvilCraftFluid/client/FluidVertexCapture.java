package cn.xm1221.AnvilCraftFluid.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 只记录、不输出顶点的假 VertexConsumer。
 *
 * 用法：mixin 把原版 {@code LiquidBlockRenderer.tesselate} 的 buffer 换成它，
 * 让原版照常把这格流体算一遍 —— 它写进来的每个顶点都被我们收下，
 * 这批顶点就是"原版渲染出来的那个流体方块"本身（位置/uv/光照全在）。
 *
 * 之后我们只做两件事：把坐标按"暴露方向水平内缩"重映射，再原样写回真 buffer。
 * 液面高度也从这批顶点里读（每个角取该角最高的顶点），不再自己算。
 */
public final class FluidVertexCapture implements VertexConsumer {

    /** 每顶点 10 个 float：0=x 1=y 2=z 3=u 4=v 5=lightU 6=lightV 7=nx 8=ny 9=nz */
    private static final int STRIDE = 10;

    private static final ThreadLocal<FluidVertexCapture> POOL =
            ThreadLocal.withInitial(FluidVertexCapture::new);

    private float[] data = new float[STRIDE * 64];
    private int vertices;
    private int cursor = -1;

    private FluidVertexCapture() {
    }

    /** 渲染线程复用同一个实例，避免每格都分配 */
    public static FluidVertexCapture get() {
        return POOL.get();
    }

    public void reset() {
        this.vertices = 0;
        this.cursor = -1;
    }

    public int vertices() {
        return this.vertices;
    }

    /** 第 v 个顶点的第 f 个分量 */
    public float get(int v, int f) {
        return this.data[v * STRIDE + f];
    }

    private void put(int f, float value) {
        if (this.cursor < 0) return;
        this.data[this.cursor * STRIDE + f] = value;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        int need = (this.vertices + 1) * STRIDE;
        if (need > this.data.length) {
            float[] bigger = new float[Math.max(need, this.data.length * 2)];
            System.arraycopy(this.data, 0, bigger, 0, this.data.length);
            this.data = bigger;
        }
        this.cursor = this.vertices++;
        int o = this.cursor * STRIDE;
        this.data[o] = x;
        this.data[o + 1] = y;
        this.data[o + 2] = z;
        this.data[o + 3] = 0.0F;
        this.data[o + 4] = 0.0F;
        this.data[o + 5] = 0.0F;
        this.data[o + 6] = 0.0F;
        this.data[o + 7] = 0.0F;
        this.data[o + 8] = 1.0F;   // 原版流体顶点固定 (0,1,0)
        this.data[o + 9] = 0.0F;
        return this;
    }

    /** 原版流体固定写 -1（白），重放时我们也用白色，所以不记 */
    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        put(3, u);
        put(4, v);
        return this;
    }

    /** 覆盖层坐标，原版流体不用，重放时也不写 */
    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        put(5, u);
        put(6, v);
        return this;
    }

    @Override
    public VertexConsumer setLight(int packedLight) {
        return setUv2(packedLight & 0xFFFF, packedLight >> 16);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        put(7, x);
        put(8, y);
        put(9, z);
        return this;
    }
}
