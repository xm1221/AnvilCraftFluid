package cn.xm1221.AnvilCraftFluid.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Headless regression checks against the actual renderer emitter, no game window required. */
public final class FluidShellGeometryCheck {
    public static void main(String[] args) throws Exception {
        checkStackJoins();
        checkCapturedSurface();
        checkReversal(new float[][] {{0,.4f,0},{0,.8f,1},{1,.4f,1},{1,.8f,0}});
        checkReversal(new float[][] {{0,.8f,0},{0,.4f,1},{1,.8f,1},{1,.4f,0}});
        checkReversal(new float[][] {{0,1,0},{1,1,0},{1,0,0},{0,0,0}});
        checkReversal(new float[][] {{0,1,1},{0,0,1},{1,0,1},{1,1,1}});
        checkReversal(new float[][] {{0,1,0},{0,0,0},{0,0,1},{0,1,1}});
        checkReversal(new float[][] {{1,1,1},{1,0,1},{1,0,0},{1,1,0}});
        System.out.println("Fluid shell geometry: stack joins, 6 winding/diagonal fixtures and capture provenance passed");
    }

    private static void checkCapturedSurface() throws Exception {
        Method heights = FluidOutlineRenderer.class.getDeclaredMethod("captureCornerHeights",
                FluidVertexCapture.class, float.class, float.class, float.class);
        heights.setAccessible(true);
        FluidVertexCapture capture = FluidVertexCapture.get();
        capture.reset();
        for (float[] v : new float[][] {{0,.001f,0},{1,.001f,0},{1,.001f,1},{0,.001f,1}})
            capture.addVertex(v[0],v[1],v[2]);
        float[] bottom = (float[]) heights.invoke(null, capture, 0f,0f,0f);
        for (float h : bottom) require(h < 0, "Bottom face incorrectly accepted as liquid surface");
        capture.reset();
        for (float[] v : new float[][] {{0,.4f,0},{0,.8f,1},{1,.6f,1},{1,.7f,0}})
            capture.addVertex(v[0],v[1],v[2]);
        float[] top = (float[]) heights.invoke(null, capture, 0f,0f,0f);
        require(Arrays.equals(top, new float[]{.4f,.7f,.8f,.6f}), "Captured top heights changed");
        capture.reset();
        for (float[] v : new float[][] {{0,.4f,.001f},{1,.7f,.001f},{1,.001f,.001f},{0,.001f,.001f}})
            capture.addVertex(v[0],v[1],v[2]);
        float[] side = (float[]) heights.invoke(null, capture, 0f,0f,0f);
        require(Arrays.equals(side, new float[]{.4f,.7f,-1,-1}), "Side upper-edge provenance lost");
        capture.reset();
    }

    private static void checkStackJoins() {
        for (int worldY = -64; worldY < 320; worldY++) {
            int sectionBase = Math.floorDiv(worldY, 16) * 16;
            float lowerTop = sectionBase + FluidOutlineRenderer.shellTopHeight(worldY & 15, .6f, false);
            require(lowerTop == worldY + 1, "Stack seam at " + worldY);
        }
        require(Math.abs(FluidOutlineRenderer.shellTopHeight(0, .8f, true) - .795f) < 1e-6f,
                "Exposed surface inset changed");
    }

    private static void checkReversal(float[][] input) throws Exception {
        List<float[]> output = new ArrayList<>();
        VertexConsumer sink = (VertexConsumer) Proxy.newProxyInstance(
                VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 3)
                        output.add(new float[]{(float)args[0], (float)args[1], (float)args[2]});
                    return method.getReturnType() == VertexConsumer.class ? proxy : null;
                });
        Class<?> emitter = Class.forName(FluidOutlineRenderer.class.getName() + "$Emitter");
        Constructor<?> constructor = emitter.getDeclaredConstructor(VertexConsumer.class, int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        Class<?>[] types = new Class<?>[23];
        Arrays.fill(types, float.class);
        Method quad = emitter.getDeclaredMethod("quad", types);
        quad.setAccessible(true);
        Object[] args = new Object[23];
        Arrays.fill(args, 0f);
        for (int i = 0; i < 4; i++) for (int axis = 0; axis < 3; axis++) args[i * 5 + axis] = input[i][axis];
        quad.invoke(constructor.newInstance(sink, -1, 0xF000F0, true), args);
        int[] order = {0,3,2,1};
        require(output.size() == 4, "Full shell quad must remain intact");
        for (int i = 0; i < 4; i++) require(Arrays.equals(output.get(i), input[order[i]]), "Winding/diagonal changed");
        // With the 0–2 diagonal preserved, reversing winding covers the SAME two triangles.
        float[] before = cross(input[0], input[1], input[2]);
        float[] after = cross(output.get(0), output.get(2), output.get(3));
        for (int i = 0; i < 3; i++) require(Math.abs(before[i] + after[i]) < 1e-6f, "Normal not reversed");
    }

    private static float[] cross(float[] a, float[] b, float[] c) {
        float x=b[0]-a[0], y=b[1]-a[1], z=b[2]-a[2];
        float u=c[0]-a[0], v=c[1]-a[1], w=c[2]-a[2];
        return new float[]{y*w-z*v, z*u-x*w, x*v-y*u};
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
