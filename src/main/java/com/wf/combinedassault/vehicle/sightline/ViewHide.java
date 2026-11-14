package com.wf.combinedassault.vehicle.sightline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.ballistics.hit.GltfReader;
import com.wf.combinedassault.ballistics.hit.NodeRole;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Seat {@code view_hide}: nodes hidden in that seat's first person. Load check keeps it off hulls. */
public final class ViewHide {

    /** Every vertex of a hidden subtree within this of one seat eye, m. */
    public static final float LIMIT = 1.5f;
    private static final Set<NodeRole> EYES = Set.of(NodeRole.SEAT, NodeRole.VIEW, NodeRole.SIGHT);

    private ViewHide() {
    }

    /** Throws naming part + node: unknown / kit node, empty subtree, or a vertex past {@link #LIMIT} of every eye. */
    public static void validate(JsonObject vehicle, GltfReader.Model gltf, List<String> kitOf) {
        if (!vehicle.has("parts")) {
            return;
        }
        List<GltfReader.Node> nodes = gltf.nodes();
        for (JsonElement element : vehicle.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (!part.has("view_hide")) {
                continue;
            }
            String id = part.get("id").getAsString();
            List<Vector3f> eyes = new ArrayList<>();
            for (GltfReader.Node node : nodes) {
                NodeRole role = NodeRole.of(node.role());
                if (EYES.contains(role) && node.iface().equals(id)) {
                    eyes.add(node.origin());
                }
            }
            if (eyes.isEmpty()) {
                throw new IllegalArgumentException("part " + id + " view_hide: no seat / view / sight socket");
            }
            for (JsonElement entry : part.getAsJsonArray("view_hide")) {
                String name = entry.getAsString();
                int at = -1;
                for (int i = 0; i < nodes.size(); i++) {
                    if (nodes.get(i).name().equals(name) && kitOf.get(i) == null) {
                        if (at >= 0) {
                            throw new IllegalArgumentException("part " + id + " view_hide " + name + ": not unique");
                        }
                        at = i;
                    }
                }
                if (at < 0) {
                    throw new IllegalArgumentException("part " + id + " view_hide " + name + ": no such node");
                }
                float worst = farthest(gltf, at, eyes);
                if (worst < 0) {
                    throw new IllegalArgumentException("part " + id + " view_hide " + name + ": draws nothing");
                }
                if (worst > LIMIT) {
                    throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                            "part %s view_hide %s: reaches %.2f m from its nearest seat eye; limit %.1f", id, name,
                            worst, LIMIT));
                }
            }
        }
    }

    /** Min over eyes of the subtree's farthest drawn vertex, rest pose; -1 = none drawn. */
    private static float farthest(GltfReader.Model gltf, int root, List<Vector3f> eyes) {
        List<GltfReader.Node> nodes = gltf.nodes();
        float[] far = new float[eyes.size()];
        boolean any = false;
        Vector3f v = new Vector3f();
        for (int i = root; i < nodes.size(); i++) {
            if (gltf.undrawn().get(i) || !under(nodes, i, root)) {
                continue;
            }
            GltfReader.Node node = nodes.get(i);
            float[] tris = node.triangles();
            for (int k = 0; k < tris.length; k += 3) {
                node.world().transformPosition(v.set(tris[k], tris[k + 1], tris[k + 2]));
                any = true;
                for (int e = 0; e < far.length; e++) {
                    far[e] = Math.max(far[e], v.distance(eyes.get(e)));
                }
            }
        }
        if (!any) {
            return -1;
        }
        float best = Float.MAX_VALUE;
        for (float f : far) {
            best = Math.min(best, f);
        }
        return best;
    }

    static boolean under(List<GltfReader.Node> nodes, int node, int root) {
        for (int at = node; at >= 0; at = nodes.get(at).parent()) {
            if (at == root) {
                return true;
            }
        }
        return false;
    }
}
