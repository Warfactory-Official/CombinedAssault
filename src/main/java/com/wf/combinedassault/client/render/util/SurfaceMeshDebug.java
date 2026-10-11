package com.wf.combinedassault.client.render.util;

import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.collision.DriveSurface;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** {@link DriveSurface} as line segments: floor tops, fill triangles, faces past the climb. */
@OnlyIn(Dist.CLIENT)
public final class SurfaceMeshDebug {

    /** Floats per segment: two points, rgba. */
    public static final int STRIDE = 10;
    private static final float LIFT = 0.02f;
    private static final int[] CARDINAL_X = {1, -1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, 1, -1};

    private SurfaceMeshDebug() {
    }

    public static float[] around(Level level, BlockPos centre, int radius, int below, int above) {
        PhysicsEngine.CellTops tops = PhysicsEngine.CellTops.live(level);
        FloatArrayList out = new FloatArrayList();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                for (int y = centre.getY() - below; y <= centre.getY() + above; y++) {
                    double base = tops.surface(x, y, z, x + 0.5, z + 0.5);
                    if (Double.isNaN(base)) {
                        continue;
                    }
                    float b = (float) base + LIFT;
                    square(out, x, b, z, 0.25f, 0.7f, 0.3f, 0.3f);
                    for (int d = 0; d < 4; d++) {
                        // Face past the climb: neighbour solid above the reach.
                        if (!Double.isNaN(tops.top(x + CARDINAL_X[d], Mth.floor(base + DriveSurface.CLIMB + 0.01), z + CARDINAL_Z[d]))) {
                            edge(out, x, b, z, CARDINAL_X[d], CARDINAL_Z[d], 1.0f, 0.15f, 0.15f, 0.9f);
                        }
                    }
                }
            }
        }
        AABB bounds = new AABB(centre.getX() - radius, centre.getY() - below, centre.getZ() - radius,
                centre.getX() + radius + 1, centre.getY() + above + 1, centre.getZ() + radius + 1);
        float[] v = new float[9];
        DriveSurface.of(level).view(level, bounds, tops).triangles(bounds, (section, t) -> {
            section.vertices(t, v);
            float x0 = section.sx * 16, y0 = section.sy * 16 + LIFT, z0 = section.sz * 16;
            for (int c = 0; c < 3; c++) {
                int p = c * 3, q = (c + 1) % 3 * 3;
                segment(out, x0 + v[p], y0 + v[p + 1], z0 + v[p + 2], x0 + v[q], y0 + v[q + 1], z0 + v[q + 2], 1.0f, 0.75f, 0.1f, 0.9f);
            }
        });
        return out.toFloatArray();
    }

    private static void square(FloatArrayList out, float x, float y, float z, float r, float g, float b, float a) {
        segment(out, x, y, z, x + 1, y, z, r, g, b, a);
        segment(out, x + 1, y, z, x + 1, y, z + 1, r, g, b, a);
        segment(out, x + 1, y, z + 1, x, y, z + 1, r, g, b, a);
        segment(out, x, y, z + 1, x, y, z, r, g, b, a);
    }

    /** Cell (x, z) edge toward (dx, dz). */
    private static void edge(FloatArrayList out, float x, float y, float z, int dx, int dz, float r, float g, float b, float a) {
        float x0 = dx > 0 ? x + 1 : x, z0 = dz > 0 ? z + 1 : z;
        if (dx != 0) {
            segment(out, x0, y, z, x0, y, z + 1, r, g, b, a);
        } else {
            segment(out, x, y, z0, x + 1, y, z0, r, g, b, a);
        }
    }

    private static void segment(FloatArrayList out, float x1, float y1, float z1, float x2, float y2, float z2,
                                float r, float g, float b, float a) {
        out.add(x1);
        out.add(y1);
        out.add(z1);
        out.add(x2);
        out.add(y2);
        out.add(z2);
        out.add(r);
        out.add(g);
        out.add(b);
        out.add(a);
    }
}
