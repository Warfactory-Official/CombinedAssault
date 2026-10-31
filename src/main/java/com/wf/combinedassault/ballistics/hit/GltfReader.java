package com.wf.combinedassault.ballistics.hit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.wflib.geom.api.Glb;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rest-pose geometry of a {@code .gltf} / {@code .glb}: every scene node with its world matrix and its triangles in
 * that node's own space, plus its animations. Side-neutral (the server reads it); triangles only, float positions and
 * float animation data only.
 */
public final class GltfReader {

    private static final int TRIANGLES = 4;
    public static final String ROLE = "combinedassaultRole";
    public static final String INTERFACE = "combinedassaultInterface";
    public static final String META = "combinedassault";

    /** @param materials glTF material names by index ({@link Node#materials} indexes this) */
    public record Model(List<Node> nodes, List<String> materials, List<Clip> clips) {
    }

    /** @param duration last key time, seconds */
    public record Clip(String name, float duration, List<Channel> channels) {
    }

    /**
     * @param node   index into {@link Model#nodes}
     * @param path   {@link #TRANSLATION}, {@link #ROTATION} or {@link #SCALE}
     * @param values 3 floats per key (4: rotation xyzw); CUBICSPLINE tangents dropped
     */
    public record Channel(int node, int path, float[] times, float[] values, boolean step) {

        /** Value at {@code time} (clamped to the keys) into {@code out}. */
        public void sample(float time, float[] out) {
            int width = path == ROTATION ? 4 : 3;
            int last = times.length - 1;
            if (time <= times[0] || last == 0) {
                System.arraycopy(values, 0, out, 0, width);
                return;
            }
            if (time >= times[last]) {
                System.arraycopy(values, last * width, out, 0, width);
                return;
            }
            int k = 0;
            while (times[k + 1] < time) {
                k++;
            }
            if (step) {
                System.arraycopy(values, k * width, out, 0, width);
                return;
            }
            float t = (time - times[k]) / (times[k + 1] - times[k]);
            int a = k * width;
            int b = a + width;
            if (path == ROTATION) {
                Quaternionf q = new Quaternionf(values[a], values[a + 1], values[a + 2], values[a + 3])
                        .slerp(new Quaternionf(values[b], values[b + 1], values[b + 2], values[b + 3]), t);
                out[0] = q.x;
                out[1] = q.y;
                out[2] = q.z;
                out[3] = q.w;
                return;
            }
            for (int i = 0; i < width; i++) {
                out[i] = values[a + i] + (values[b + i] - values[a + i]) * t;
            }
        }
    }

    public static final int TRANSLATION = 0;
    public static final int ROTATION = 1;
    public static final int SCALE = 2;

    /**
     * @param parent    index into {@link Model#nodes}, or -1
     * @param world     rest-pose node-to-model matrix
     * @param local     rest-pose node-to-parent matrix
     * @param triangles node-space positions, 9 floats per triangle
     * @param materials per triangle, index into {@link Model#materials} or -1
     * @param role      {@code extras.combinedassaultRole}, "" if none
     * @param iface     {@code extras.combinedassaultInterface}, "" if none
     * @param meta      {@code extras.combinedassault}: role parameters
     * @param primitives first triangle of each primitive, ascending
     */
    public record Node(String name, int parent, Matrix4f world, Matrix4f local, float[] triangles, int[] materials,
                       int[] primitives, String role, String iface, JsonObject meta) {

        public Vector3f origin() {
            return world.getTranslation(new Vector3f());
        }

        public Quaternionf rotation() {
            return world.getUnnormalizedRotation(new Quaternionf());
        }
    }

    private GltfReader() {
    }

    public static Model read(byte[] file, Glb.Buffers buffers) throws IOException {
        Glb glb = Glb.read(file, buffers);
        JsonObject gltf = glb.json();
        JsonArray nodes = array(gltf, "nodes");
        List<Node> out = new ArrayList<>();
        JsonArray scenes = array(gltf, "scenes");
        JsonArray roots;
        if (scenes.isEmpty()) {
            roots = new JsonArray();
            boolean[] child = new boolean[nodes.size()];
            for (JsonElement node : nodes) {
                for (JsonElement c : array(node.getAsJsonObject(), "children")) {
                    child[c.getAsInt()] = true;
                }
            }
            for (int i = 0; i < child.length; i++) {
                if (!child[i]) {
                    roots.add(i);
                }
            }
        } else {
            int scene = gltf.has("scene") ? gltf.get("scene").getAsInt() : 0;
            roots = array(scenes.get(scene).getAsJsonObject(), "nodes");
        }
        Map<Integer, Mesh> meshes = new HashMap<>();
        int[] outIndex = new int[nodes.size()];
        java.util.Arrays.fill(outIndex, -1);
        for (JsonElement root : roots) {
            visit(glb, nodes, root.getAsInt(), -1, new Matrix4f(), out, meshes, outIndex);
        }
        List<String> materials = new ArrayList<>();
        JsonArray materialArray = array(gltf, "materials");
        for (int i = 0; i < materialArray.size(); i++) {
            JsonObject material = materialArray.get(i).getAsJsonObject();
            materials.add(material.has("name") ? material.get("name").getAsString() : "");
        }
        return new Model(List.copyOf(out), List.copyOf(materials), clips(glb, outIndex));
    }

    /** Channels on nodes outside the scene dropped; weights (morph) ignored. */
    private static List<Clip> clips(Glb glb, int[] outIndex) throws IOException {
        List<Clip> clips = new ArrayList<>();
        JsonArray animations = array(glb.json(), "animations");
        for (int a = 0; a < animations.size(); a++) {
            JsonObject animation = animations.get(a).getAsJsonObject();
            JsonArray samplers = array(animation, "samplers");
            List<Channel> channels = new ArrayList<>();
            float duration = 0;
            for (JsonElement element : array(animation, "channels")) {
                JsonObject target = element.getAsJsonObject().getAsJsonObject("target");
                int path = switch (target.get("path").getAsString()) {
                    case "translation" -> TRANSLATION;
                    case "rotation" -> ROTATION;
                    case "scale" -> SCALE;
                    default -> -1;
                };
                if (path < 0 || !target.has("node") || outIndex[target.get("node").getAsInt()] < 0) {
                    continue;
                }
                JsonObject sampler = samplers.get(element.getAsJsonObject().get("sampler").getAsInt()).getAsJsonObject();
                String interpolation = sampler.has("interpolation") ? sampler.get("interpolation").getAsString() : "LINEAR";
                int width = path == ROTATION ? 4 : 3;
                float[] times = glb.floats(sampler.get("input").getAsInt(), 1);
                float[] values = glb.floats(sampler.get("output").getAsInt(), width);
                if (interpolation.equals("CUBICSPLINE")) {
                    float[] middle = new float[times.length * width];
                    for (int k = 0; k < times.length; k++) {
                        System.arraycopy(values, (k * 3 + 1) * width, middle, k * width, width);
                    }
                    values = middle;
                }
                duration = Math.max(duration, times[times.length - 1]);
                channels.add(new Channel(outIndex[target.get("node").getAsInt()], path, times, values,
                        interpolation.equals("STEP")));
            }
            String name = animation.has("name") ? animation.get("name").getAsString() : "animation" + a;
            clips.add(new Clip(name, duration, List.copyOf(channels)));
        }
        return List.copyOf(clips);
    }

    /** {@code meshes}: decoded once per mesh index; nodes sharing a mesh share its arrays. */
    private static void visit(Glb glb, JsonArray nodes, int index, int parent,
                              Matrix4f parentWorld, List<Node> out, Map<Integer, Mesh> meshes, int[] outIndex)
            throws IOException {
        JsonObject node = nodes.get(index).getAsJsonObject();
        Matrix4f local = Glb.local(node);
        Matrix4f world = new Matrix4f(parentWorld).mul(local);
        Mesh mesh = node.has("mesh") ? mesh(glb, node.get("mesh").getAsInt(), meshes)
                : new Mesh(new float[0], new int[0], new int[0]);
        String name = node.has("name") ? node.get("name").getAsString() : "node" + index;
        int self = out.size();
        outIndex[index] = self;
        JsonObject extras = node.has("extras") && node.get("extras").isJsonObject() ? node.getAsJsonObject("extras") : null;
        JsonObject meta = extras != null && extras.has(META) ? extras.getAsJsonObject(META) : new JsonObject();
        String role = extras != null && extras.has(ROLE) ? extras.get(ROLE).getAsString() : "";
        String iface = extras != null && extras.has(INTERFACE) ? extras.get(INTERFACE).getAsString() : "";
        out.add(new Node(name, parent, world, local, mesh.triangles, mesh.materials, mesh.primitives, role, iface, meta));
        for (JsonElement child : array(node, "children")) {
            visit(glb, nodes, child.getAsInt(), self, world, out, meshes, outIndex);
        }
    }

    private static Mesh mesh(Glb glb, int index, Map<Integer, Mesh> meshes)
            throws IOException {
        Mesh cached = meshes.get(index);
        if (cached == null) {
            cached = triangles(glb, array(glb.json(), "meshes").get(index).getAsJsonObject());
            meshes.put(index, cached);
        }
        return cached;
    }

    private record Mesh(float[] triangles, int[] materials, int[] primitives) {
    }

    private static Mesh triangles(Glb glb, JsonObject mesh) throws IOException {
        List<float[]> parts = new ArrayList<>();
        List<Integer> partMaterials = new ArrayList<>();
        int total = 0;
        for (JsonElement element : array(mesh, "primitives")) {
            JsonObject primitive = element.getAsJsonObject();
            int mode = primitive.has("mode") ? primitive.get("mode").getAsInt() : TRIANGLES;
            if (mode != TRIANGLES) {
                continue; // points / lines / strips: nothing a shot can land on as authored
            }
            float[] positions = positions(glb,
                    primitive.getAsJsonObject("attributes").get("POSITION").getAsInt());
            int[] indices = primitive.has("indices")
                    ? glb.indices(primitive.get("indices").getAsInt())
                    : null;
            int count = indices == null ? positions.length / 3 : indices.length;
            float[] tris = new float[count / 3 * 9];
            for (int i = 0; i < count / 3 * 3; i++) {
                int v = indices == null ? i : indices[i];
                System.arraycopy(positions, v * 3, tris, i * 3, 3);
            }
            parts.add(tris);
            partMaterials.add(primitive.has("material") ? primitive.get("material").getAsInt() : -1);
            total += tris.length;
        }
        float[] out = new float[total];
        int[] materials = new int[total / 9];
        int[] starts = new int[parts.size()];
        int at = 0;
        for (int p = 0; p < parts.size(); p++) {
            float[] part = parts.get(p);
            starts[p] = at / 9;
            System.arraycopy(part, 0, out, at, part.length);
            java.util.Arrays.fill(materials, at / 9, (at + part.length) / 9, partMaterials.get(p));
            at += part.length;
        }
        return new Mesh(out, materials, starts);
    }

    private static float[] positions(Glb glb, int accessorIndex) throws IOException {
        if (!"VEC3".equals(glb.accessor(accessorIndex).get("type").getAsString())) {
            throw new IOException("POSITION accessor " + accessorIndex + " is not VEC3");
        }
        return glb.floats(accessorIndex, 3);
    }

    private static JsonArray array(JsonObject object, String key) {
        return Glb.array(object, key);
    }
}
