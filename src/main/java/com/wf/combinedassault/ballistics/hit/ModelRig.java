package com.wf.combinedassault.ballistics.hit;

import com.google.gson.JsonObject;
import com.wf.wflib.physics.api.ConvexHull;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vehicle skeleton from glTF node roles. Frames: {@code body} (one, world = identity), {@code part} / {@code pitch}
 * (interface = part id, one each per part). Every node rides its nearest frame (self or ancestor). Colliders: convex
 * mesh nodes, vehicle frame.
 *
 * @param nodeFrame per glTF node (reader order): frame index
 */
public record ModelRig(List<Frame> frames, int[] nodeFrame, List<Collider> colliders) {

    private static final Set<String> COLLIDER_KEYS = Set.of("main", "deck", "solid", "world", "name", "hit");
    /** Vertex weld and coplanarity, blocks. */
    private static final float WELD = 1.0e-4f;
    private static final float PLANE = 1.0e-4f;
    /** cos 1 deg. */
    private static final float SAME_FACE = 0.99985f;

    /**
     * @param parent frame index; -1 for the body
     * @param meta   {@code extras.combinedassault}
     */
    public record Frame(int node, String name, NodeRole role, String part, int parent, Matrix4f world, JsonObject meta) {
    }

    /** @param world false => vehicles only: blocks, hill fill, providers pass through (ramp lip below the ground) */
    public record Collider(int frame, String node, ConvexHull hull, boolean main, boolean deck, boolean solid, boolean world,
                           String name) {
    }

    public static final int BODY = 0;

    public int part(String id) {
        return find(NodeRole.PART, id);
    }

    public int pitch(String id) {
        return find(NodeRole.PITCH, id);
    }

    private int find(NodeRole role, String id) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).role == role && frames.get(i).part.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public static ModelRig of(String source, List<GltfReader.Node> nodes) {
        List<Frame> frames = new ArrayList<>();
        int[] nodeFrame = new int[nodes.size()];
        int body = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (NodeRole.of(nodes.get(i).role()) == NodeRole.BODY) {
                if (body >= 0) {
                    throw new IllegalArgumentException(source + ": two body nodes, " + nodes.get(body).name() + " and "
                            + nodes.get(i).name());
                }
                body = i;
            }
        }
        if (body < 0) {
            throw new IllegalArgumentException(source + ": no node with role body");
        }
        if (!nodes.get(body).world().equals(new Matrix4f(), 1.0e-5f)) {
            throw new IllegalArgumentException(source + ": body node " + nodes.get(body).name()
                    + " is not at the model origin, unrotated, unscaled");
        }
        frames.add(new Frame(body, nodes.get(body).name(), NodeRole.BODY, "", -1, new Matrix4f(), nodes.get(body).meta()));
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            GltfReader.Node node = nodes.get(i);
            NodeRole role = NodeRole.of(node.role());
            int parentFrame = node.parent() < 0 ? -1 : nodeFrame[node.parent()];
            if (i == body) {
                nodeFrame[i] = BODY;
                continue;
            }
            if (parentFrame < 0 && role != null) {
                throw new IllegalArgumentException(source + ": " + node.name() + " (" + node.role()
                        + ") is not under the body node");
            }
            if (role == NodeRole.PART || role == NodeRole.PITCH) {
                if (node.iface().isEmpty()) {
                    throw new IllegalArgumentException(source + ": " + role.key() + " node " + node.name()
                            + " names no part (combinedassaultInterface)");
                }
                Integer prev = seen.put(role.key() + ":" + node.iface(), i);
                if (prev != null) {
                    throw new IllegalArgumentException(source + ": " + role.key() + " " + node.iface() + " on "
                            + nodes.get(prev).name() + " and " + node.name());
                }
                frames.add(new Frame(i, node.name(), role, node.iface(), parentFrame, new Matrix4f(node.world()), node.meta()));
                nodeFrame[i] = frames.size() - 1;
            } else {
                nodeFrame[i] = Math.max(parentFrame, BODY);
            }
        }
        List<Collider> colliders = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            GltfReader.Node node = nodes.get(i);
            if (NodeRole.of(node.role()) != NodeRole.COLLIDER) {
                continue;
            }
            JsonObject meta = node.meta();
            for (String key : meta.keySet()) {
                if (!COLLIDER_KEYS.contains(key)) {
                    throw new IllegalArgumentException(source + ": collider " + node.name() + ": unknown key " + key);
                }
            }
            boolean main = meta.has("main") && meta.get("main").getAsBoolean();
            if (main && nodeFrame[i] != BODY) {
                throw new IllegalArgumentException(source + ": main collider " + node.name() + " rides a part");
            }
            ConvexHull hull;
            try {
                hull = hull(node);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(source + ": collider " + node.name() + ": " + e.getMessage(), e);
            }
            colliders.add(new Collider(nodeFrame[i], node.name(), hull, main,
                    meta.has("deck") && meta.get("deck").getAsBoolean(),
                    !meta.has("solid") || meta.get("solid").getAsBoolean(),
                    !meta.has("world") || meta.get("world").getAsBoolean(),
                    meta.has("name") ? meta.get("name").getAsString() : node.name()));
        }
        if (colliders.stream().filter(Collider::main).count() > 1) {
            throw new IllegalArgumentException(source + ": more than one main collider");
        }
        return new ModelRig(List.copyOf(frames), nodeFrame, List.copyOf(colliders));
    }

    /** Triangles (world) -> welded vertices; faces = one per primitive, else one loop per plane. */
    static ConvexHull hull(GltfReader.Node node) {
        float[] tris = node.triangles();
        if (tris.length == 0) {
            throw new IllegalArgumentException("no mesh");
        }
        List<Vector3f> points = new ArrayList<>();
        int[] index = new int[tris.length / 3];
        Vector3f v = new Vector3f();
        for (int k = 0; k < index.length; k++) {
            node.world().transformPosition(v.set(tris[k * 3], tris[k * 3 + 1], tris[k * 3 + 2]));
            int found = -1;
            for (int p = 0; p < points.size(); p++) {
                if (points.get(p).distanceSquared(v) < WELD * WELD) {
                    found = p;
                    break;
                }
            }
            if (found < 0) {
                points.add(new Vector3f(v));
                found = points.size() - 1;
            }
            index[k] = found;
        }
        float[] flat = new float[points.size() * 3];
        for (int p = 0; p < points.size(); p++) {
            flat[p * 3] = points.get(p).x;
            flat[p * 3 + 1] = points.get(p).y;
            flat[p * 3 + 2] = points.get(p).z;
        }
        int[] starts = node.primitives();
        if (starts.length > 1) {
            // One primitive per face, fan-triangulated: loop = vertices in first-seen order. Exact authored faces.
            int[][] faces = new int[starts.length][];
            for (int f = 0; f < starts.length; f++) {
                int end = f + 1 < starts.length ? starts[f + 1] : index.length / 3;
                List<Integer> loop = new ArrayList<>();
                for (int k = starts[f] * 3; k < end * 3; k++) {
                    if (!loop.contains(index[k])) {
                        loop.add(index[k]);
                    }
                }
                faces[f] = loop.stream().mapToInt(Integer::intValue).toArray();
            }
            return ConvexHull.of(flat, faces);
        }
        List<Vector3f> normals = new ArrayList<>();
        List<Float> offsets = new ArrayList<>();
        List<List<Integer>> faceVertices = new ArrayList<>();
        Vector3f a = new Vector3f(), b = new Vector3f(), n = new Vector3f();
        for (int t = 0; t < index.length / 3; t++) {
            Vector3f p0 = points.get(index[t * 3]), p1 = points.get(index[t * 3 + 1]), p2 = points.get(index[t * 3 + 2]);
            p1.sub(p0, a).cross(p2.sub(p0, b), n);
            if (n.lengthSquared() < 1.0e-12f) {
                continue;
            }
            n.normalize();
            float d = n.dot(p0);
            int plane = -1;
            for (int f = 0; f < normals.size() && plane < 0; f++) {
                Vector3f fn = normals.get(f);
                float fd = offsets.get(f);
                // A quad planar within ConvexHull's 1e-4 splits into triangles ~1e-3 rad apart: merge on the plane.
                if (fn.dot(n) > SAME_FACE && Math.abs(fn.dot(p0) - fd) < PLANE && Math.abs(fn.dot(p1) - fd) < PLANE
                        && Math.abs(fn.dot(p2) - fd) < PLANE) {
                    plane = f;
                }
            }
            if (plane < 0) {
                normals.add(new Vector3f(n));
                offsets.add(d);
                faceVertices.add(new ArrayList<>());
                plane = normals.size() - 1;
            }
            for (int c = 0; c < 3; c++) {
                if (!faceVertices.get(plane).contains(index[t * 3 + c])) {
                    faceVertices.get(plane).add(index[t * 3 + c]);
                }
            }
        }
        int[][] faces = new int[faceVertices.size()][];
        for (int f = 0; f < faces.length; f++) {
            faces[f] = loop(points, faceVertices.get(f), normals.get(f));
        }
        return ConvexHull.of(flat, faces);
    }

    /** Convex polygon vertices ordered counter-clockwise about {@code normal}. */
    private static int[] loop(List<Vector3f> points, List<Integer> vertices, Vector3f normal) {
        Vector3f centre = new Vector3f();
        for (int i : vertices) {
            centre.add(points.get(i));
        }
        centre.div(vertices.size());
        Vector3f u = new Vector3f(points.get(vertices.get(0))).sub(centre).normalize();
        Vector3f w = new Vector3f(normal).cross(u);
        Integer[] order = vertices.toArray(Integer[]::new);
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> {
            Vector3f d = new Vector3f(points.get(i)).sub(centre);
            return Math.atan2(d.dot(w), d.dot(u));
        }));
        int[] out = new int[order.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = order[i];
        }
        return out;
    }
}
