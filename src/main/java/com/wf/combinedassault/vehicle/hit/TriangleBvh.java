package com.wf.combinedassault.vehicle.hit;

/**
 * Median-split BVH over one node's triangles, node frame; nearest ray hit (Moller-Trumbore, two-sided): exact point,
 * face normal, per-triangle armour for the plate model and surface material for effects.
 */
public final class TriangleBvh {

    private static final int LEAF = 4;

    /** 9 floats per triangle, in BVH order. */
    private final float[] tris;
    /** mm RHA per triangle ({@code armor_<mm>} material), 0 = none; BVH order. */
    private final float[] armor;
    /** {@link SurfaceMaterial} ordinal per triangle; BVH order. */
    private final byte[] material;
    /** Per BVH node: 6 bounds. */
    private final float[] bounds;
    /** Per BVH node: leaf => {@code -(first + 1)}, count in {@link #count}; else right child (left = node + 1). */
    private final int[] child;
    private final int[] count;
    private int nodes;

    public TriangleBvh(float[] triangles, float[] armour, byte[] materials) {
        int n = triangles.length / 9;
        int[] order = new int[n];
        float[] centre = new float[n * 3];
        for (int t = 0; t < n; t++) {
            order[t] = t;
            for (int a = 0; a < 3; a++) {
                centre[t * 3 + a] = (triangles[t * 9 + a] + triangles[t * 9 + 3 + a] + triangles[t * 9 + 6 + a]) / 3;
            }
        }
        int capacity = Math.max(1, 2 * n); // median split, leaves 2..LEAF => < 2n nodes
        bounds = new float[capacity * 6];
        child = new int[capacity];
        count = new int[capacity];
        build(triangles, centre, order, 0, n);
        tris = new float[n * 9];
        armor = new float[n];
        material = new byte[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(triangles, order[i] * 9, tris, i * 9, 9);
            armor[i] = armour[order[i]];
            material[i] = materials[order[i]];
        }
    }

    public int triangleCount() {
        return armor.length;
    }

    private int build(float[] triangles, float[] centre, int[] order, int from, int to) {
        int node = nodes++;
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = from; i < to; i++) {
            int t = order[i] * 9;
            for (int v = 0; v < 9; v += 3) {
                for (int a = 0; a < 3; a++) {
                    b[a] = Math.min(b[a], triangles[t + v + a]);
                    b[a + 3] = Math.max(b[a + 3], triangles[t + v + a]);
                }
            }
        }
        System.arraycopy(b, 0, bounds, node * 6, 6);
        if (to - from <= LEAF) {
            child[node] = -(from + 1);
            count[node] = to - from;
            return node;
        }
        int axis = 0;
        for (int a = 1; a < 3; a++) {
            if (b[a + 3] - b[a] > b[axis + 3] - b[axis]) {
                axis = a;
            }
        }
        int mid = (from + to) >>> 1;
        select(centre, order, from, to - 1, mid, axis);
        build(triangles, centre, order, from, mid);
        child[node] = build(triangles, centre, order, mid, to);
        return node;
    }

    /** Quickselect: order[k] is the k-th by centre along axis. */
    private static void select(float[] centre, int[] order, int lo, int hi, int k, int axis) {
        while (lo < hi) {
            float pivot = centre[order[(lo + hi) >>> 1] * 3 + axis];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (centre[order[i] * 3 + axis] < pivot) i++;
                while (centre[order[j] * 3 + axis] > pivot) j--;
                if (i <= j) {
                    int swap = order[i];
                    order[i] = order[j];
                    order[j] = swap;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                return;
            }
        }
    }

    /**
     * Nearest hit along {@code o + t d}, {@code 0 <= t < limit}; writes {@code t} to {@code out[0]} and returns the
     * triangle, or -1.
     */
    public int trace(float ox, float oy, float oz, float dx, float dy, float dz, float limit, float[] out) {
        float ix = 1 / dx, iy = 1 / dy, iz = 1 / dz;
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = 0;
        float best = limit;
        int hit = -1;
        while (top > 0) {
            int node = stack[--top];
            if (!slab(node * 6, ox, oy, oz, ix, iy, iz, best)) {
                continue;
            }
            int c = child[node];
            if (c < 0) {
                int first = -c - 1;
                for (int t = first; t < first + count[node]; t++) {
                    float d = intersect(t * 9, ox, oy, oz, dx, dy, dz);
                    if (d >= 0 && d < best) {
                        best = d;
                        hit = t;
                    }
                }
            } else if (top + 2 <= stack.length) {
                stack[top++] = c;
                stack[top++] = node + 1;
            }
        }
        out[0] = best;
        return hit;
    }

    private boolean slab(int b, float ox, float oy, float oz, float ix, float iy, float iz, float limit) {
        float t0 = (bounds[b] - ox) * ix, t1 = (bounds[b + 3] - ox) * ix;
        float near = Math.min(t0, t1), far = Math.max(t0, t1);
        t0 = (bounds[b + 1] - oy) * iy;
        t1 = (bounds[b + 4] - oy) * iy;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));
        t0 = (bounds[b + 2] - oz) * iz;
        t1 = (bounds[b + 5] - oz) * iz;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));
        return far >= Math.max(near, 0) && near < limit;
    }

    private float intersect(int t, float ox, float oy, float oz, float dx, float dy, float dz) {
        float ax = tris[t], ay = tris[t + 1], az = tris[t + 2];
        float e1x = tris[t + 3] - ax, e1y = tris[t + 4] - ay, e1z = tris[t + 5] - az;
        float e2x = tris[t + 6] - ax, e2y = tris[t + 7] - ay, e2z = tris[t + 8] - az;
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1.0e-12f) {
            return -1;
        }
        float inv = 1 / det;
        float sx = ox - ax, sy = oy - ay, sz = oz - az;
        float u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) {
            return -1;
        }
        float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        float v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0 || u + v > 1) {
            return -1;
        }
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    /** Unit face normal of triangle {@code t}, node frame, into {@code out}; winding sign unspecified. */
    public void normal(int t, float[] out) {
        int i = t * 9;
        float e1x = tris[i + 3] - tris[i], e1y = tris[i + 4] - tris[i + 1], e1z = tris[i + 5] - tris[i + 2];
        float e2x = tris[i + 6] - tris[i], e2y = tris[i + 7] - tris[i + 1], e2z = tris[i + 8] - tris[i + 2];
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[0] = nx / l;
        out[1] = ny / l;
        out[2] = nz / l;
    }

    /** 9 floats per triangle, node frame. */
    public float[] triangles() {
        return tris;
    }

    public boolean hasArmor() {
        for (float mm : armor) {
            if (mm > 0) {
                return true;
            }
        }
        return false;
    }

    public float armor(int t) {
        return armor[t];
    }

    public SurfaceMaterial material(int t) {
        return SurfaceMaterial.byId(material[t]);
    }

    /** 6 floats. */
    public float[] rootBounds() {
        float[] out = new float[6];
        System.arraycopy(bounds, 0, out, 0, 6);
        return out;
    }
}
