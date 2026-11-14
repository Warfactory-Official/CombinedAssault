package com.wf.combinedassault.vehicle.sightline;

import com.wf.combinedassault.ballistics.hit.GltfReader;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelDataManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.List;

/**
 * What GemRender draws of one vehicle type, indexed like its {@link HitModel} nodes: opaque triangles in each node's
 * rigid frame (scale baked), front face counter-clockwise. Out: undrawn nodes, BLEND materials, swept rotors.
 *
 * @param tris   per node: 9 floats per triangle, null = draws nothing here
 * @param culled per node, per triangle: single-sided
 * @param sphere per node: centre xyz + radius per triangle, then the node's own last
 * @param world  per node: rest node frame -> model
 * @param frames {@code <role> <interface>} of each frame node, else ""
 */
public record SightMesh(List<String> names, int[] parents, @Nullable float[][] tris, boolean[][] culled,
                        float[][] sphere, Matrix4f[] world, List<String> kitOf, List<String> frames) {

    public static SightMesh of(ModelData model, HitModel hits) throws IOException {
        ModelDataManager.Grafted grafted = ModelDataManager.grafted(ResourceLocation.parse(model.source()), model.grafts());
        GltfReader.Model gltf = grafted.gltf();
        List<GltfReader.Node> nodes = gltf.nodes();
        if (nodes.size() != hits.nodes().size()) {
            throw new IllegalStateException(model.source() + ": " + nodes.size() + " nodes, hit model "
                    + hits.nodes().size());
        }
        int n = nodes.size();
        String[] names = new String[n];
        String[] frames = new String[n];
        int[] parents = new int[n];
        float[][] tris = new float[n][];
        boolean[][] culled = new boolean[n][];
        float[][] sphere = new float[n][];
        Matrix4f[] world = new Matrix4f[n];
        Vector3f v = new Vector3f();
        for (int i = 0; i < n; i++) {
            GltfReader.Node node = nodes.get(i);
            names[i] = node.name();
            frames[i] = node.role().equals("part") || node.role().equals("pitch") ? node.role() + " " + node.iface() : "";
            parents[i] = node.parent();
            world[i] = new Matrix4f().translationRotate(node.origin(), node.rotation());
            if (gltf.undrawn().get(i) || hits.nodes().get(i).mesh().disc() != null || node.triangles().length == 0) {
                continue;
            }
            Matrix4f toFrame = new Matrix4f(world[i]).invert().mul(node.world());
            boolean mirrored = node.world().determinant3x3() < 0;
            float[] src = node.triangles();
            int[] materials = node.materials();
            int kept = 0;
            float[] out = new float[src.length];
            boolean[] cull = new boolean[materials.length];
            for (int t = 0; t < materials.length; t++) {
                GltfReader.Look look = materials[t] < 0 ? null : gltf.looks().get(materials[t]);
                if (look != null && look.blend()) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    // glTF: negative determinant => clockwise front; swap two corners to keep CCW.
                    int from = t * 9 + (mirrored && k > 0 ? 3 - k : k) * 3;
                    toFrame.transformPosition(v.set(src[from], src[from + 1], src[from + 2]));
                    out[kept * 9 + k * 3] = v.x;
                    out[kept * 9 + k * 3 + 1] = v.y;
                    out[kept * 9 + k * 3 + 2] = v.z;
                }
                cull[kept++] = look == null || !look.doubleSided();
            }
            if (kept == 0) {
                continue;
            }
            tris[i] = java.util.Arrays.copyOf(out, kept * 9);
            culled[i] = java.util.Arrays.copyOf(cull, kept);
            sphere[i] = spheres(tris[i]);
        }
        return new SightMesh(List.of(names), parents, tris, culled, sphere, world, grafted.kitOf(), List.of(frames));
    }

    /** Per triangle centroid + farthest corner, then the node's (AABB centre, farthest vertex). */
    private static float[] spheres(float[] t) {
        int count = t.length / 9;
        float[] out = new float[(count + 1) * 4];
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int k = 0; k < count; k++) {
            int b = k * 9;
            float cx = (t[b] + t[b + 3] + t[b + 6]) / 3, cy = (t[b + 1] + t[b + 4] + t[b + 7]) / 3,
                    cz = (t[b + 2] + t[b + 5] + t[b + 8]) / 3;
            float r = 0;
            for (int c = 0; c < 3; c++) {
                float dx = t[b + c * 3] - cx, dy = t[b + c * 3 + 1] - cy, dz = t[b + c * 3 + 2] - cz;
                r = Math.max(r, dx * dx + dy * dy + dz * dz);
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], t[b + c * 3 + a]);
                    hi[a] = Math.max(hi[a], t[b + c * 3 + a]);
                }
            }
            out[k * 4] = cx;
            out[k * 4 + 1] = cy;
            out[k * 4 + 2] = cz;
            out[k * 4 + 3] = (float) Math.sqrt(r);
        }
        int o = count * 4;
        out[o] = (lo[0] + hi[0]) / 2;
        out[o + 1] = (lo[1] + hi[1]) / 2;
        out[o + 2] = (lo[2] + hi[2]) / 2;
        float r = 0;
        for (int k = 0; k < t.length; k += 3) {
            float dx = t[k] - out[o], dy = t[k + 1] - out[o + 1], dz = t[k + 2] - out[o + 2];
            r = Math.max(r, dx * dx + dy * dy + dz * dz);
        }
        out[o + 3] = (float) Math.sqrt(r);
        return out;
    }

    /** Pitch frame node of part {@code part}, else its part frame, else -1. */
    public int bore(String part) {
        int at = frames.indexOf("pitch " + part);
        return at >= 0 ? at : frames.indexOf("part " + part);
    }

    /** Index of the base-model node {@code name} (not grafted from a kit), or -1. */
    public int node(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name) && kitOf.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    public boolean under(int node, int root) {
        for (int at = node; at >= 0; at = parents[at]) {
            if (at == root) {
                return true;
            }
        }
        return false;
    }

    /** Farthest drawn vertex of {@code root}'s subtree from {@code eye}, rest model frame; -1 = none drawn. */
    public float reach(int root, Vector3f eye) {
        float far = -1;
        Vector3f v = new Vector3f();
        for (int i = root; i < names.size(); i++) {
            if (tris[i] == null || !under(i, root)) {
                continue;
            }
            for (int k = 0; k < tris[i].length; k += 3) {
                far = Math.max(far, world[i].transformPosition(v.set(tris[i][k], tris[i][k + 1], tris[i][k + 2])).distance(eye));
            }
        }
        return far;
    }
}
