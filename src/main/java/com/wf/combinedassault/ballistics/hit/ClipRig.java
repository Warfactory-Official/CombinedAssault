package com.wf.combinedassault.ballistics.hit;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** A model's node tree + clips, side-neutral: node worlds for given clip times. Indices = {@link HitMesh#nodes}. */
public final class ClipRig {

    private final int[] parent;
    private final Matrix4f[] local;
    private final Matrix4f[] restWorld;
    private final List<GltfReader.Clip> clips;

    private ClipRig(int[] parent, Matrix4f[] local, Matrix4f[] restWorld, List<GltfReader.Clip> clips) {
        this.parent = parent;
        this.local = local;
        this.restWorld = restWorld;
        this.clips = clips;
    }

    /** Null: no clips. */
    @Nullable
    public static ClipRig of(GltfReader.Model model) {
        if (model.clips().isEmpty()) {
            return null;
        }
        int n = model.nodes().size();
        int[] parent = new int[n];
        Matrix4f[] local = new Matrix4f[n];
        Matrix4f[] world = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            GltfReader.Node node = model.nodes().get(i);
            parent[i] = node.parent();
            local[i] = node.local();
            world[i] = node.world();
        }
        return new ClipRig(parent, local, world, model.clips());
    }

    public List<GltfReader.Clip> clips() {
        return clips;
    }

    public int nodeCount() {
        return parent.length;
    }

    /** Node-to-model matrix at rest. */
    public Matrix4f restWorld(int node) {
        return restWorld[node];
    }

    /**
     * Node-to-model matrices with clip {@code c} at {@code times[c]} seconds (NaN: not applied), later clips winning.
     *
     * @param moved out: node or an ancestor animated; other entries of {@code world} are left untouched
     */
    public void pose(float[] times, Matrix4f[] world, boolean[] moved) {
        int n = parent.length;
        Vector3f[] t = new Vector3f[n];
        Quaternionf[] r = new Quaternionf[n];
        Vector3f[] s = new Vector3f[n];
        float[] value = new float[4];
        for (int c = 0; c < clips.size(); c++) {
            if (Float.isNaN(times[c])) {
                continue;
            }
            for (GltfReader.Channel channel : clips.get(c).channels()) {
                int node = channel.node();
                if (r[node] == null) {
                    t[node] = local[node].getTranslation(new Vector3f());
                    r[node] = local[node].getUnnormalizedRotation(new Quaternionf()).normalize();
                    s[node] = local[node].getScale(new Vector3f());
                }
                channel.sample(times[c], value);
                switch (channel.path()) {
                    case GltfReader.TRANSLATION -> t[node].set(value[0], value[1], value[2]);
                    case GltfReader.ROTATION -> r[node].set(value[0], value[1], value[2], value[3]).normalize();
                    default -> s[node].set(value[0], value[1], value[2]);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            boolean animated = r[i] != null;
            moved[i] = animated || parent[i] >= 0 && moved[parent[i]];
            if (!moved[i]) {
                continue;
            }
            Matrix4f out = world[i] == null ? world[i] = new Matrix4f() : world[i];
            if (parent[i] >= 0 && moved[parent[i]]) {
                out.set(world[parent[i]]);
            } else if (parent[i] >= 0) {
                out.set(restWorld[parent[i]]);
            } else {
                out.identity();
            }
            if (animated) {
                out.translate(t[i]).rotate(r[i]).scale(s[i]);
            } else {
                out.mul(local[i]);
            }
        }
    }
}
