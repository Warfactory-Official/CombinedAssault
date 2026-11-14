package com.wf.combinedassault.render.gem;

import com.wf.gemrender.gltf.GltfPaletteLayout;
import com.wf.gemrender.gltf.MeshGeometry;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.skin.BoneAttributeCodec;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Rest-pose top-down silhouettes, one per bone group, rasterised on the CPU into a single atlas. Pure data in and
 * out, any thread. Group = bone whose subtree owns the triangle (nearest listed ancestor), {@link #HULL} otherwise.
 * Sprite pixels are bone-local {@code (x, z)}: image column grows with +x, row with +z.
 */
public final class SchematicBake {
    /** Bone name of the group holding every triangle no listed bone owns. */
    public static final String HULL = "";

    static final int TARGET_PX = 160;
    static final int PAD = 2;
    static final int ATLAS_WIDTH = 512;
    static final int ATLAS_MAX_HEIGHT = 1024;
    private static final int EMPTY = 0x00FFFFFF;
    private static final int OUTLINE = 0xFFFFFFFF;
    private static final int TRI_FLOATS = 10;

    /**
     * @param x,y,w,h atlas pixels; {@code min*, max*} bone-local extent in blocks the pixels cover
     */
    public record Sprite(String bone, int x, int y, int w, int h, float minX, float minZ, float maxX, float maxZ) {
    }

    /**
     * @param argb          row-major, straight alpha
     * @param sprites       parents before children
     * @param centreX,centreZ model-space middle of the whole silhouette
     */
    public record Result(int width, int height, int[] argb, List<Sprite> sprites, float centreX, float centreZ) {
    }

    private SchematicBake() {
    }

    /**
     * @param rest  rest palette ({@code layout.size()} entries)
     * @param bones node names that become their own sprite; names the model lacks are skipped
     * @return null if no geometry
     */
    public static Result bake(List<MeshGeometry> meshes, GltfPaletteLayout layout, Matrix4f[] rest, List<String> bones) {
        NodeTable table = layout.nodeTable();
        int nodes = table.nodeCount();
        int[] parents = table.parentSlots();

        int[] slotNode = new int[layout.size()];
        for (int i = 0; i < nodes; i++) {
            slotNode[i] = i;
        }
        for (GltfPaletteLayout.SkinBlock block : layout.skins()) {
            for (int j = 0; j < block.jointCount(); j++) {
                slotNode[block.base() + j] = block.jointSlots()[j];
            }
        }

        List<String> names = new ArrayList<>();
        List<Integer> boneNodes = new ArrayList<>();
        names.add(HULL);
        boneNodes.add(-1);
        int[] ownerOfNode = new int[nodes];
        Arrays.fill(ownerOfNode, -1);
        for (String bone : bones) {
            int node = table.slotOfName(bone);
            if (node >= 0 && ownerOfNode[node] < 0) {
                ownerOfNode[node] = names.size();
                names.add(bone);
                boneNodes.add(node);
            }
        }
        int groups = names.size();
        int[] groupOfNode = new int[nodes];
        for (int n = 0; n < nodes; n++) {
            int cur = n;
            while (cur >= 0 && ownerOfNode[cur] < 0) {
                cur = parents[cur];
            }
            groupOfNode[n] = cur < 0 ? 0 : ownerOfNode[cur];
        }
        Matrix4f[] inverse = new Matrix4f[groups];
        inverse[0] = new Matrix4f();
        for (int g = 1; g < groups; g++) {
            inverse[g] = new Matrix4f(rest[boneNodes.get(g)]).invert();
        }

        FloatBuf[] tris = new FloatBuf[groups];
        float[][] bounds = new float[groups][];
        for (int g = 0; g < groups; g++) {
            tris[g] = new FloatBuf();
            bounds[g] = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        }
        float[] model = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                Float.MAX_VALUE, -Float.MAX_VALUE};

        Vector3f[] p = {new Vector3f(), new Vector3f(), new Vector3f()};
        Vector3f local = new Vector3f();
        for (MeshGeometry mesh : meshes) {
            for (int i = 0; i + 2 < mesh.indexCount(); i += 3) {
                int group = -1;
                for (int k = 0; k < 3; k++) {
                    int v = mesh.index(i + k);
                    int slot = dominantSlot(mesh, v);
                    rest[slot].transformPosition(mesh.position(v, 0), mesh.position(v, 1), mesh.position(v, 2), p[k]);
                    if (k == 0) {
                        group = groupOfNode[slotNode[slot]];
                    }
                }
                float ux = p[1].x - p[0].x;
                float uy = p[1].y - p[0].y;
                float uz = p[1].z - p[0].z;
                float vx = p[2].x - p[0].x;
                float vy = p[2].y - p[0].y;
                float vz = p[2].z - p[0].z;
                float nx = uy * vz - uz * vy;
                float ny = uz * vx - ux * vz;
                float nz = ux * vy - uy * vx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1e-9f) {
                    continue;
                }
                int at = tris[group].size;
                for (int k = 0; k < 3; k++) {
                    inverse[group].transformPosition(p[k], local);
                    tris[group].add(local.x).add(local.z).add(p[k].y);
                    bounds[group][0] = Math.min(bounds[group][0], local.x);
                    bounds[group][1] = Math.min(bounds[group][1], local.z);
                    bounds[group][2] = Math.max(bounds[group][2], local.x);
                    bounds[group][3] = Math.max(bounds[group][3], local.z);
                    model[0] = Math.min(model[0], p[k].x);
                    model[1] = Math.min(model[1], p[k].z);
                    model[2] = Math.max(model[2], p[k].x);
                    model[3] = Math.max(model[3], p[k].z);
                    model[4] = Math.min(model[4], p[k].y);
                    model[5] = Math.max(model[5], p[k].y);
                }
                tris[group].add(Math.abs(ny) / length);
                assert tris[group].size - at == TRI_FLOATS;
            }
        }
        if (model[0] > model[2]) {
            return null;
        }

        List<Integer> used = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            if (tris[g].size > 0) {
                used.add(g);
            }
        }
        int[] depth = new int[groups];
        for (int g = 1; g < groups; g++) {
            int d = 0;
            for (int cur = parents[boneNodes.get(g)]; cur >= 0; cur = parents[cur]) {
                d++;
            }
            depth[g] = d + 1;
        }
        used.sort(Comparator.comparingInt((Integer g) -> depth[g]).thenComparingInt(g -> g));

        float ppb = TARGET_PX / Math.max(1e-3f, Math.max(model[2] - model[0], model[3] - model[1]));
        int[][] place = null;
        int height = 0;
        for (int attempt = 0; attempt < 8 && place == null; attempt++) {
            int[][] trial = new int[used.size()][4];
            height = pack(used, bounds, ppb, trial);
            if (height > 0 && height <= ATLAS_MAX_HEIGHT) {
                place = trial;
            } else {
                ppb *= 0.75f;
            }
        }
        if (place == null) {
            throw new IllegalStateException("silhouettes do not fit " + ATLAS_WIDTH + "x" + ATLAS_MAX_HEIGHT);
        }

        int[] argb = new int[ATLAS_WIDTH * height];
        Arrays.fill(argb, EMPTY);
        List<Sprite> sprites = new ArrayList<>(used.size());
        for (int s = 0; s < used.size(); s++) {
            int g = used.get(s);
            int[] r = place[s];
            float minX = bounds[g][0] - PAD / ppb;
            float minZ = bounds[g][1] - PAD / ppb;
            Sprite sprite = new Sprite(names.get(g), r[0], r[1], r[2], r[3], minX, minZ,
                    minX + r[2] / ppb, minZ + r[3] / ppb);
            sprites.add(sprite);
            raster(tris[g], sprite, ppb, model[4], model[5], argb);
            outline(sprite, argb);
        }
        return new Result(ATLAS_WIDTH, height, argb, List.copyOf(sprites),
                (model[0] + model[2]) * 0.5f, (model[1] + model[3]) * 0.5f);
    }

    private static int dominantSlot(MeshGeometry mesh, int vertex) {
        int packed = mesh.packedJoints(vertex);
        int best = 0;
        for (int k = 1; k < BoneAttributeCodec.INFLUENCES; k++) {
            if (mesh.weightChannel(vertex, k) > mesh.weightChannel(vertex, best)) {
                best = k;
            }
        }
        return BoneAttributeCodec.unpackJoint(packed, best);
    }

    /** Shelf pack; fills {@code out[s] = {x, y, w, h}}. */
    private static int pack(List<Integer> used, float[][] bounds, float ppb, int[][] out) {
        int[] order = new int[used.size()];
        int[] w = new int[used.size()];
        int[] h = new int[used.size()];
        for (int s = 0; s < order.length; s++) {
            float[] b = bounds[used.get(s)];
            w[s] = (int) Math.ceil((b[2] - b[0]) * ppb) + 2 * PAD;
            h[s] = (int) Math.ceil((b[3] - b[1]) * ppb) + 2 * PAD;
            if (w[s] > ATLAS_WIDTH) {
                return -1;
            }
            order[s] = s;
        }
        Integer[] sorted = new Integer[order.length];
        Arrays.setAll(sorted, i -> i);
        Arrays.sort(sorted, Comparator.comparingInt((Integer s) -> -h[s]));
        int x = 0;
        int y = 0;
        int shelf = 0;
        for (int s : sorted) {
            if (x + w[s] > ATLAS_WIDTH) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            out[s][0] = x;
            out[s][1] = y;
            out[s][2] = w[s];
            out[s][3] = h[s];
            x += w[s];
            shelf = Math.max(shelf, h[s]);
        }
        return y + shelf;
    }

    private static void raster(FloatBuf tris, Sprite sprite, float ppb, float minY, float maxY, int[] argb) {
        float[] depth = new float[sprite.w() * sprite.h()];
        Arrays.fill(depth, -Float.MAX_VALUE);
        float span = Math.max(1e-6f, maxY - minY);
        float[] d = tris.data;
        for (int t = 0; t < tris.size; t += TRI_FLOATS) {
            float x0 = (d[t] - sprite.minX()) * ppb;
            float z0 = (d[t + 1] - sprite.minZ()) * ppb;
            float x1 = (d[t + 3] - sprite.minX()) * ppb;
            float z1 = (d[t + 4] - sprite.minZ()) * ppb;
            float x2 = (d[t + 6] - sprite.minX()) * ppb;
            float z2 = (d[t + 7] - sprite.minZ()) * ppb;
            float area = (x1 - x0) * (z2 - z0) - (x2 - x0) * (z1 - z0);
            if (Math.abs(area) < 1e-8f) {
                continue;
            }
            int px0 = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int px1 = Math.min(sprite.w() - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int pz0 = Math.max(0, (int) Math.floor(Math.min(z0, Math.min(z1, z2))));
            int pz1 = Math.min(sprite.h() - 1, (int) Math.ceil(Math.max(z0, Math.max(z1, z2))));
            float shade = d[t + 9];
            for (int pz = pz0; pz <= pz1; pz++) {
                for (int px = px0; px <= px1; px++) {
                    float cx = px + 0.5f;
                    float cz = pz + 0.5f;
                    float w1 = ((cx - x0) * (z2 - z0) - (x2 - x0) * (cz - z0)) / area;
                    float w2 = ((x1 - x0) * (cz - z0) - (cx - x0) * (z1 - z0)) / area;
                    float w0 = 1 - w1 - w2;
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) {
                        continue;
                    }
                    float y = w0 * d[t + 2] + w1 * d[t + 5] + w2 * d[t + 8];
                    int at = pz * sprite.w() + px;
                    if (y <= depth[at]) {
                        continue;
                    }
                    depth[at] = y;
                    float height = Math.min(1, Math.max(0, (y - minY) / span));
                    float v = 0.30f + 0.45f * shade + 0.25f * height;
                    int grey = 40 + (int) (Math.min(1, v) * 170);
                    argb[(sprite.y() + pz) * ATLAS_WIDTH + sprite.x() + px] = 0xFF000000 | grey << 16 | grey << 8 | grey;
                }
            }
        }
    }

    /** One pixel ring around the silhouette; {@link #PAD} keeps it inside the sprite. */
    private static void outline(Sprite sprite, int[] argb) {
        boolean[] solid = new boolean[sprite.w() * sprite.h()];
        for (int y = 0; y < sprite.h(); y++) {
            for (int x = 0; x < sprite.w(); x++) {
                solid[y * sprite.w() + x] = (argb[(sprite.y() + y) * ATLAS_WIDTH + sprite.x() + x] >>> 24) != 0;
            }
        }
        for (int y = 0; y < sprite.h(); y++) {
            for (int x = 0; x < sprite.w(); x++) {
                if (solid[y * sprite.w() + x]) {
                    continue;
                }
                boolean edge = x > 0 && solid[y * sprite.w() + x - 1]
                        || x + 1 < sprite.w() && solid[y * sprite.w() + x + 1]
                        || y > 0 && solid[(y - 1) * sprite.w() + x]
                        || y + 1 < sprite.h() && solid[(y + 1) * sprite.w() + x];
                if (edge) {
                    argb[(sprite.y() + y) * ATLAS_WIDTH + sprite.x() + x] = OUTLINE;
                }
            }
        }
    }

    private static final class FloatBuf {
        float[] data = new float[TRI_FLOATS * 64];
        int size;

        FloatBuf add(float value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
            return this;
        }
    }
}
