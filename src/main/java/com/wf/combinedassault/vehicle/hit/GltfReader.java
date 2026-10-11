package com.wf.combinedassault.vehicle.hit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rest-pose geometry of a {@code .gltf} / {@code .glb}: every scene node with its world matrix and its triangles in
 * that node's own space, plus its animations. Side-neutral (the server reads it); triangles only, float positions and
 * float animation data only.
 */
public final class GltfReader {

    private static final int FLOAT = 5126;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final int TRIANGLES = 4;
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    public static final String ROLE = "combinedassaultRole";
    public static final String INTERFACE = "combinedassaultInterface";
    public static final String META = "combinedassault";

    /** External buffer: {@code uri} relative to the model, else {@code extras.resourceLocation} (ns:path). */
    @FunctionalInterface
    public interface Buffers {
        byte[] open(@org.jetbrains.annotations.Nullable String uri, @org.jetbrains.annotations.Nullable String resourceLocation)
                throws IOException;
    }

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

    public static Model read(byte[] file, Buffers buffers) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        JsonObject gltf;
        ByteBuffer glbBinary = null;
        if (file.length >= 12 && data.getInt(0) == GLB_MAGIC) {
            int jsonLength = data.getInt(12);
            if (data.getInt(16) != CHUNK_JSON) {
                throw new IOException("GLB: first chunk is not JSON");
            }
            gltf = JsonParser.parseString(new String(file, 20, jsonLength, StandardCharsets.UTF_8)).getAsJsonObject();
            int binAt = 20 + jsonLength;
            if (binAt + 8 <= file.length) {
                int binLength = data.getInt(binAt);
                glbBinary = ByteBuffer.wrap(file, binAt + 8, binLength).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
        } else {
            gltf = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        List<ByteBuffer> bufferData = new ArrayList<>();
        for (JsonElement element : array(gltf, "buffers")) {
            JsonObject buffer = element.getAsJsonObject();
            JsonObject extras = buffer.has("extras") ? buffer.getAsJsonObject("extras") : new JsonObject();
            String location = extras.has("resourceLocation") ? extras.get("resourceLocation").getAsString() : null;
            if (!buffer.has("uri") && location == null) {
                if (glbBinary == null) {
                    throw new IOException("buffer without uri or resourceLocation outside a GLB");
                }
                bufferData.add(glbBinary);
                continue;
            }
            String uri = buffer.has("uri") ? buffer.get("uri").getAsString() : null;
            byte[] bytes;
            if (uri != null && uri.startsWith("data:")) {
                bytes = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
            } else {
                bytes = buffers.open(uri, location);
            }
            bufferData.add(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        }

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
            visit(gltf, bufferData, nodes, root.getAsInt(), -1, new Matrix4f(), out, meshes, outIndex);
        }
        List<String> materials = new ArrayList<>();
        JsonArray materialArray = array(gltf, "materials");
        for (int i = 0; i < materialArray.size(); i++) {
            JsonObject material = materialArray.get(i).getAsJsonObject();
            materials.add(material.has("name") ? material.get("name").getAsString() : "");
        }
        return new Model(List.copyOf(out), List.copyOf(materials), clips(gltf, bufferData, outIndex));
    }

    /** Channels on nodes outside the scene dropped; weights (morph) ignored. */
    private static List<Clip> clips(JsonObject gltf, List<ByteBuffer> buffers, int[] outIndex) throws IOException {
        List<Clip> clips = new ArrayList<>();
        JsonArray animations = array(gltf, "animations");
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
                float[] times = floats(gltf, buffers, sampler.get("input").getAsInt(), 1);
                float[] values = floats(gltf, buffers, sampler.get("output").getAsInt(), width);
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

    private static float[] floats(JsonObject gltf, List<ByteBuffer> buffers, int accessorIndex, int width)
            throws IOException {
        JsonObject accessor = accessor(gltf, accessorIndex);
        if (accessor.get("componentType").getAsInt() != FLOAT) {
            throw new IOException("animation accessor " + accessorIndex + " is not float (quantized clips unsupported)");
        }
        int count = accessor.get("count").getAsInt();
        View view = view(gltf, buffers, accessor, width * 4);
        float[] out = new float[count * width];
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < width; c++) {
                out[i * width + c] = view.data.getFloat(view.offset + i * view.stride + c * 4);
            }
        }
        return out;
    }

    /** {@code meshes}: decoded once per mesh index; nodes sharing a mesh share its arrays. */
    private static void visit(JsonObject gltf, List<ByteBuffer> buffers, JsonArray nodes, int index, int parent,
                              Matrix4f parentWorld, List<Node> out, Map<Integer, Mesh> meshes, int[] outIndex)
            throws IOException {
        JsonObject node = nodes.get(index).getAsJsonObject();
        Matrix4f local = local(node);
        Matrix4f world = new Matrix4f(parentWorld).mul(local);
        Mesh mesh = node.has("mesh") ? mesh(gltf, buffers, node.get("mesh").getAsInt(), meshes)
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
            visit(gltf, buffers, nodes, child.getAsInt(), self, world, out, meshes, outIndex);
        }
    }

    private static Mesh mesh(JsonObject gltf, List<ByteBuffer> buffers, int index, Map<Integer, Mesh> meshes)
            throws IOException {
        Mesh cached = meshes.get(index);
        if (cached == null) {
            cached = triangles(gltf, buffers, array(gltf, "meshes").get(index).getAsJsonObject());
            meshes.put(index, cached);
        }
        return cached;
    }

    private static Matrix4f local(JsonObject node) {
        if (node.has("matrix")) {
            float[] m = new float[16];
            JsonArray values = node.getAsJsonArray("matrix");
            for (int i = 0; i < 16; i++) {
                m[i] = values.get(i).getAsFloat();
            }
            return new Matrix4f().set(m); // column-major == glTF order
        }
        Matrix4f m = new Matrix4f();
        if (node.has("translation")) {
            JsonArray t = node.getAsJsonArray("translation");
            m.translate(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
        }
        if (node.has("rotation")) {
            JsonArray r = node.getAsJsonArray("rotation");
            m.rotate(new Quaternionf(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(),
                    r.get(3).getAsFloat()));
        }
        if (node.has("scale")) {
            JsonArray s = node.getAsJsonArray("scale");
            m.scale(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());
        }
        return m;
    }

    private record Mesh(float[] triangles, int[] materials, int[] primitives) {
    }

    private static Mesh triangles(JsonObject gltf, List<ByteBuffer> buffers, JsonObject mesh) throws IOException {
        List<float[]> parts = new ArrayList<>();
        List<Integer> partMaterials = new ArrayList<>();
        int total = 0;
        for (JsonElement element : array(mesh, "primitives")) {
            JsonObject primitive = element.getAsJsonObject();
            int mode = primitive.has("mode") ? primitive.get("mode").getAsInt() : TRIANGLES;
            if (mode != TRIANGLES) {
                continue; // points / lines / strips: nothing a shot can land on as authored
            }
            float[] positions = positions(gltf, buffers,
                    primitive.getAsJsonObject("attributes").get("POSITION").getAsInt());
            int[] indices = primitive.has("indices")
                    ? indices(gltf, buffers, primitive.get("indices").getAsInt())
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

    private static float[] positions(JsonObject gltf, List<ByteBuffer> buffers, int accessorIndex) throws IOException {
        JsonObject accessor = accessor(gltf, accessorIndex);
        if (accessor.get("componentType").getAsInt() != FLOAT || !"VEC3".equals(accessor.get("type").getAsString())) {
            throw new IOException("POSITION accessor " + accessorIndex + " is not float VEC3 (quantized meshes unsupported)");
        }
        int count = accessor.get("count").getAsInt();
        View view = view(gltf, buffers, accessor, 12);
        float[] out = new float[count * 3];
        for (int i = 0; i < count; i++) {
            int at = view.offset + i * view.stride;
            out[i * 3] = view.data.getFloat(at);
            out[i * 3 + 1] = view.data.getFloat(at + 4);
            out[i * 3 + 2] = view.data.getFloat(at + 8);
        }
        return out;
    }

    private static int[] indices(JsonObject gltf, List<ByteBuffer> buffers, int accessorIndex) throws IOException {
        JsonObject accessor = accessor(gltf, accessorIndex);
        int type = accessor.get("componentType").getAsInt();
        int size = switch (type) {
            case UNSIGNED_BYTE -> 1;
            case UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT -> 4;
            default -> throw new IOException("index accessor " + accessorIndex + " has component type " + type);
        };
        int count = accessor.get("count").getAsInt();
        View view = view(gltf, buffers, accessor, size);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int at = view.offset + i * view.stride;
            out[i] = switch (size) {
                case 1 -> view.data.get(at) & 0xFF;
                case 2 -> view.data.getShort(at) & 0xFFFF;
                default -> view.data.getInt(at);
            };
        }
        return out;
    }

    private record View(ByteBuffer data, int offset, int stride) {
    }

    private static View view(JsonObject gltf, List<ByteBuffer> buffers, JsonObject accessor, int elementSize)
            throws IOException {
        if (accessor.has("sparse") || !accessor.has("bufferView")) {
            throw new IOException("sparse or view-less accessors unsupported");
        }
        JsonObject view = array(gltf, "bufferViews").get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
        ByteBuffer data = buffers.get(view.get("buffer").getAsInt());
        int offset = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0)
                + (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
        int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : elementSize;
        return new View(data, offset, stride);
    }

    private static JsonObject accessor(JsonObject gltf, int index) {
        return array(gltf, "accessors").get(index).getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }
}
