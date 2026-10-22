package com.wf.combinedassault.vehicle.hit;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.pack.CombinedPacks;
import com.wf.combinedassault.vehicle.light.VehicleLight;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ModelData} per vehicle def from its {@code model} glTF (pack files, both sides). {@code "hitbox": false} =>
 * no hit mesh. Part {@code mounts} grafted under their node first; a grafted part-target socket without an interface
 * targets the carrier part.
 */
public final class ModelDataManager {

    private static final Set<String> HIT_KEYS = Set.of("system", "side", "unit", "spin", "health", "swept", "skip",
            "index", "road_wheel");
    /** Frame-only: fill the part's {@code rot_info} ({@link MountDefaults}). */
    private static final Set<String> FRAME_KEYS = Set.of("x_rot_min", "x_rot_max", "y_rot_min", "y_rot_max");
    /** A rotor disc thinner than this is padded to it: a shot must be able to find it. */
    private static final float DISC_THICKNESS = 0.3f;
    /** Disc height from vertices past this share of the radius: blades, not mast, hub or mast radar. */
    private static final float BLADE_SPAN = 0.5f;
    private static final Pattern ARMOR = Pattern.compile("(?i)armou?r[_ ]?(\\d+(?:\\.\\d+)?)");
    /** Material token: drawn only, never hit (gen_hulls.py: never collides). */
    private static final Pattern DECOR = Pattern.compile("(?i)(?:^|[_ .])decor(?:$|[_ .])");
    private static final Set<NodeRole> PART_TARGET = EnumSet.of(NodeRole.SEAT, NodeRole.DISMOUNT, NodeRole.VIEW,
            NodeRole.SIGHT, NodeRole.SIGHT_LENS, NodeRole.BOLT, NodeRole.EJECT);

    private ModelDataManager() {
    }

    /** Throws on the first broken model, naming it: a vehicle without a rig cannot exist. */
    public static Map<ResourceLocation, ModelData> build(Map<ResourceLocation, JsonObject> vehicles) {
        long start = System.nanoTime();
        ImmutableMap.Builder<ResourceLocation, ModelData> built = ImmutableMap.builder();
        int triangles = 0;
        int mounts = 0;
        for (var entry : vehicles.entrySet()) {
            ModelData data;
            try {
                data = build(entry.getKey(), entry.getValue());
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException(entry.getKey() + ": " + e.getMessage(), e);
            }
            built.put(entry.getKey(), data);
            mounts += data.mounts().mounts().size();
            if (data.hits() != null) {
                for (HitMesh.Node node : data.hits().nodes()) {
                    triangles += node.mesh() == null ? 0 : node.mesh().triangleCount();
                }
            }
        }
        Map<ResourceLocation, ModelData> out = built.build();
        CombinedAssault.LOGGER.info("Vehicle models: {}, {} hit triangles, {} sockets in {} ms", out.size(), triangles,
                mounts, (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    static ModelData build(ResourceLocation id, JsonObject vehicle) throws IOException {
        if (!vehicle.has("model")) {
            throw new IllegalArgumentException("no model");
        }
        ResourceLocation modelId = ResourceLocation.parse(vehicle.get("model").getAsString());
        GltfReader.Model gltf = gltf(modelId);
        // Per node: kit of the kit-scoped mount it was grafted from, else null.
        List<String> kitOf = new ArrayList<>(java.util.Collections.nCopies(gltf.nodes().size(), (String) null));
        if (vehicle.has("parts")) {
            for (JsonElement element : vehicle.getAsJsonArray("parts")) {
                JsonObject part = element.getAsJsonObject();
                if (part.has("mounts")) {
                    for (JsonElement entry : part.getAsJsonArray("mounts")) {
                        JsonObject mount = entry.getAsJsonObject();
                        String kit = mount.has("kit")
                                ? ResourceLocation.parse(mount.get("kit").getAsString()).toString() : null;
                        gltf = graft(gltf, gltf(ResourceLocation.parse(mount.get("model").getAsString())),
                                mount.get("node").getAsString(), part.get("id").getAsString());
                        while (kitOf.size() < gltf.nodes().size()) {
                            kitOf.add(kit);
                        }
                    }
                }
            }
        }
        String source = modelId.toString();
        ModelRig rig = ModelRig.of(source, gltf.nodes());

        List<ModelMounts.Link> links = new ArrayList<>();
        List<ModelMounts.Mount> mounts = new ArrayList<>();
        List<VehicleLight> lights = new ArrayList<>();
        List<ModelMounts.Wheel> wheels = new ArrayList<>();
        for (int i = 0; i < gltf.nodes().size(); i++) {
            GltfReader.Node node = gltf.nodes().get(i);
            NodeRole role = NodeRole.of(node.role());
            links.add(new ModelMounts.Link(node.name(), node.parent(), node.world()));
            if (node.meta().has("road_wheel") && node.meta().get("road_wheel").getAsBoolean()) {
                if (role != null || node.triangles().length == 0) {
                    throw new IllegalArgumentException(source + ": road_wheel " + node.name()
                            + " must be a drawn mesh node without a role");
                }
                wheels.add(new ModelMounts.Wheel(node.name(), node.origin()));
            }
            if (kitOf.get(i) != null && role != null && role != NodeRole.BOLT && role != NodeRole.EJECT && role != NodeRole.SIGHT
                    && role != NodeRole.SIGHT_LENS && role != NodeRole.HIT && role != NodeRole.HIT_SURFACE) {
                throw new IllegalArgumentException(source + ": node " + node.name() + " (" + role.key()
                        + ") in the mount of kit " + kitOf.get(i) + "; kit mounts carry bolt, eject, sight, sight_lens, hit and hit_surface nodes only");
            }
            if (role == NodeRole.LIGHT) {
                lights.add(VehicleLight.read(i, node.name(), node.meta()));
            } else if (role != null && role.kind == NodeRole.Kind.SOCKET) {
                float[] world = node.triangles().clone();
                Vector3f v = new Vector3f();
                for (int t = 0; t < world.length; t += 3) {
                    node.world().transformPosition(v.set(world[t], world[t + 1], world[t + 2]));
                    world[t] = v.x;
                    world[t + 1] = v.y;
                    world[t + 2] = v.z;
                }
                mounts.add(new ModelMounts.Mount(i, node.name(), role, node.iface(),
                        node.meta().has("index") ? node.meta().get("index").getAsInt() : -1, node.origin(),
                        node.rotation(), node.world().getScale(new Vector3f()), world, kitOf.get(i)));
            }
        }
        ModelMounts modelMounts = new ModelMounts(List.copyOf(links), List.copyOf(mounts), List.copyOf(wheels), rig);
        ClipRig clips = ClipRig.of(gltf);
        if (vehicle.has("hitbox") && !vehicle.get("hitbox").getAsBoolean()) {
            return new ModelData(source, rig, null, modelMounts, clips, List.copyOf(lights));
        }
        return new ModelData(source, rig, hitMesh(source, gltf, rig, kitOf), modelMounts, clips, List.copyOf(lights));
    }

    /** @param kitOf per node: kit of the kit-scoped mount it was grafted from, else null */
    public static HitMesh hitMesh(String source, GltfReader.Model gltf, ModelRig rig, List<String> kitOf) {
        float[] armour = new float[gltf.materials().size()];
        boolean[] decor = new boolean[armour.length];
        byte[] surfaces = new byte[armour.length];
        for (int m = 0; m < armour.length; m++) {
            Matcher matcher = ARMOR.matcher(gltf.materials().get(m));
            armour[m] = matcher.find() ? Float.parseFloat(matcher.group(1)) : 0;
            decor[m] = DECOR.matcher(gltf.materials().get(m)).find();
            surfaces[m] = (byte) SurfaceMaterial.of(gltf.materials().get(m)).ordinal();
        }

        // Marker: role node (not frame) or under one; never hit surface unless a surface override.
        boolean[] marker = new boolean[gltf.nodes().size()];
        for (int i = 0; i < marker.length; i++) {
            GltfReader.Node node = gltf.nodes().get(i);
            NodeRole role = NodeRole.of(node.role());
            marker[i] = role != null && role.kind != NodeRole.Kind.FRAME || node.parent() >= 0 && marker[node.parent()];
        }
        // Surface override (hit_surface, collider "hit"): replaces its parent's drawn subtree.
        boolean[] surface = new boolean[marker.length];
        boolean[] replacing = new boolean[marker.length];
        for (int n = 0; n < marker.length; n++) {
            GltfReader.Node node = gltf.nodes().get(n);
            NodeRole role = NodeRole.of(node.role());
            surface[n] = role == NodeRole.HIT_SURFACE || role == NodeRole.COLLIDER && node.meta().has("hit")
                    && node.meta().get("hit").getAsBoolean();
            if (surface[n]) {
                if (node.parent() < 0) {
                    throw new IllegalArgumentException(source + ": hit surface " + node.name() + " has no parent to replace");
                }
                replacing[node.parent()] = true;
            }
        }
        boolean[] replaced = new boolean[marker.length];
        for (int n = 0; n < marker.length; n++) {
            int parent = gltf.nodes().get(n).parent();
            boolean above = parent >= 0 && replaced[parent];
            if (replacing[n] && above) {
                throw new IllegalArgumentException(source + ": hit surface under " + gltf.nodes().get(n).name()
                        + " inside a subtree another hit surface replaces");
            }
            replaced[n] = replacing[n] || above;
        }

        List<HitMesh.Node> nodes = new ArrayList<>();
        for (int n = 0; n < marker.length; n++) {
            GltfReader.Node node = gltf.nodes().get(n);
            NodeRole role = NodeRole.of(node.role());
            boolean frame = role != null && role.kind == NodeRole.Kind.FRAME;
            JsonObject meta = role == null || frame || role == NodeRole.HIT || role == NodeRole.HIT_SURFACE
                    ? node.meta() : new JsonObject();
            for (String key : meta.keySet()) {
                if (!HIT_KEYS.contains(key) && !(frame && FRAME_KEYS.contains(key))) {
                    throw new IllegalArgumentException(source + ": node " + node.name() + ": unknown key " + key);
                }
            }
            boolean module = role == NodeRole.HIT;
            String component = meta.has("system") ? meta.get("system").getAsString() : "";
            if (module && component.isEmpty()) {
                throw new IllegalArgumentException(source + ": hit node " + node.name() + " names no system");
            }
            if (!component.isEmpty() && !component.equalsIgnoreCase("none") && ComponentKind.byName(component) == null) {
                throw new IllegalArgumentException(source + ": node " + node.name() + ": system " + component
                        + "; expected " + ComponentKind.names() + " or none");
            }
            boolean skip = !surface[n] && (marker[n] ? !module : replaced[n])
                    || meta.has("skip") && meta.get("skip").getAsBoolean();
            float health = meta.has("health") ? meta.get("health").getAsFloat() : 0;
            String spin = meta.has("spin") ? meta.get("spin").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
            if (!spin.isEmpty() && !spin.equals("cw") && !spin.equals("ccw")) {
                throw new IllegalArgumentException(source + ": node " + node.name() + ": spin " + spin
                        + "; expected cw or ccw");
            }
            Quaternionf rotation = node.rotation();
            Vector3f origin = node.origin();
            // Node frame = rigid part of the world matrix; scale goes into the vertices.
            Matrix4f toFrame = new Matrix4f().translationRotate(origin, rotation).invert().mul(node.world());
            int[] faceMaterials = skip ? new int[0] : node.materials().clone();
            float[] tris = skip ? new float[0] : node.triangles().clone();
            int kept = 0;
            for (int t = 0; t < faceMaterials.length; t++) {
                if (faceMaterials[t] < 0 || !decor[faceMaterials[t]]) {
                    System.arraycopy(tris, t * 9, tris, kept * 9, 9);
                    faceMaterials[kept++] = faceMaterials[t];
                }
            }
            if (kept < faceMaterials.length) {
                tris = java.util.Arrays.copyOf(tris, kept * 9);
                faceMaterials = java.util.Arrays.copyOf(faceMaterials, kept);
            }
            Vector3f v = new Vector3f();
            for (int i = 0; i < tris.length; i += 3) {
                toFrame.transformPosition(v.set(tris[i], tris[i + 1], tris[i + 2]));
                tris[i] = v.x;
                tris[i + 1] = v.y;
                tris[i + 2] = v.z;
            }
            boolean swept = meta.has("swept") ? meta.get("swept").getAsBoolean() : !module && spins(gltf.nodes(), node);
            TriangleBvh mesh = null;
            HitMesh.Disc disc = null;
            if (tris.length > 0 && swept) {
                disc = disc(tris);
            } else if (tris.length > 0) {
                float[] faceArmour = new float[tris.length / 9];
                byte[] faceSurface = new byte[faceArmour.length];
                for (int t = 0; t < faceArmour.length; t++) {
                    int m = faceMaterials[t];
                    faceArmour[t] = m < 0 ? 0 : armour[m];
                    faceSurface[t] = m < 0 ? 0 : surfaces[m];
                }
                mesh = new TriangleBvh(tris, faceArmour, faceSurface);
            }
            nodes.add(new HitMesh.Node(node.name(), node.parent(), module, rig.nodeFrame()[n], component,
                    meta.has("side") ? meta.get("side").getAsString() : "",
                    meta.has("unit") ? meta.get("unit").getAsString() : "", spin, health, rotation, origin, mesh, disc,
                    kitOf.get(n)));
        }
        return new HitMesh(source, List.copyOf(nodes));
    }

    static GltfReader.Model gltf(ResourceLocation modelId) throws IOException {
        byte[] file = CombinedPacks.asset(modelId).orElseThrow(() -> new IOException("model " + modelId + " in no pack"));
        String dir = modelId.getPath().contains("/")
                ? modelId.getPath().substring(0, modelId.getPath().lastIndexOf('/') + 1) : "";
        return GltfReader.read(file, (uri, location) -> {
            ResourceLocation buffer = uri != null
                    ? ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), dir + uri)
                    : ResourceLocation.parse(location);
            return CombinedPacks.asset(buffer).orElseThrow(() -> new IOException("buffer " + buffer + " of " + modelId
                    + " missing"));
        });
    }

    /** {@code mount}'s roots re-parented under node {@code at}; its part-target sockets without an interface => {@code part}; its clips appended. */
    static GltfReader.Model graft(GltfReader.Model base, GltfReader.Model mount, String at, String part) {
        int attach = -1;
        for (int i = 0; i < base.nodes().size(); i++) {
            if (base.nodes().get(i).name().equals(at)) {
                if (attach >= 0) {
                    throw new IllegalArgumentException("mount node " + at + " of part " + part + " is not unique");
                }
                attach = i;
            }
        }
        if (attach < 0) {
            throw new IllegalArgumentException("part " + part + " mounts on node " + at + ", which the model lacks");
        }
        Matrix4f world = base.nodes().get(attach).world();
        int nodeBase = base.nodes().size();
        int materialBase = base.materials().size();
        List<GltfReader.Node> nodes = new ArrayList<>(base.nodes());
        for (GltfReader.Node node : mount.nodes()) {
            int[] materials = node.materials().clone();
            for (int t = 0; t < materials.length; t++) {
                materials[t] = materials[t] < 0 ? -1 : materials[t] + materialBase;
            }
            NodeRole role = NodeRole.of(node.role());
            if (role == NodeRole.BODY || role == NodeRole.PART || role == NodeRole.PITCH) {
                throw new IllegalArgumentException("mount model of part " + part + ": " + role.key() + " node "
                        + node.name());
            }
            String iface = PART_TARGET.contains(role) && node.iface().isEmpty() ? part : node.iface();
            nodes.add(new GltfReader.Node(node.name(), node.parent() < 0 ? attach : node.parent() + nodeBase,
                    new Matrix4f(world).mul(node.world()), node.parent() < 0 ? new Matrix4f(node.world()) : node.local(),
                    node.triangles(), materials, node.primitives(), node.role(), iface, node.meta()));
        }
        List<String> materials = new ArrayList<>(base.materials());
        materials.addAll(mount.materials());
        List<GltfReader.Clip> clips = new ArrayList<>(base.clips());
        for (GltfReader.Clip clip : mount.clips()) {
            List<GltfReader.Channel> channels = new ArrayList<>(clip.channels().size());
            for (GltfReader.Channel c : clip.channels()) {
                channels.add(new GltfReader.Channel(c.node() + nodeBase, c.path(), c.times(), c.values(), c.step()));
            }
            clips.add(new GltfReader.Clip(PartClipTracks.mountClip(clip.name(), part), clip.duration(),
                    List.copyOf(channels)));
        }
        return new GltfReader.Model(List.copyOf(nodes), List.copyOf(materials), List.copyOf(clips));
    }

    private static HitMesh.Disc disc(float[] tris) {
        float[] b = bounds(tris);
        int axis = 0;
        for (int a = 1; a < 3; a++) {
            if (b[a + 3] - b[a] < b[axis + 3] - b[axis]) {
                axis = a;
            }
        }
        int u = (axis + 1) % 3;
        int w = (axis + 2) % 3;
        float radius = 0;
        for (int i = 0; i < tris.length; i += 3) {
            radius = Math.max(radius, (float) Math.hypot(tris[i + u], tris[i + w]));
        }
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 3) {
            if (Math.hypot(tris[i + u], tris[i + w]) > BLADE_SPAN * radius) {
                lo = Math.min(lo, tris[i + axis]);
                hi = Math.max(hi, tris[i + axis]);
            }
        }
        float mid = (lo + hi) / 2;
        float half = Math.max((hi - lo) / 2, DISC_THICKNESS / 2);
        return new HitMesh.Disc(axis, radius, mid - half, mid + half);
    }

    private static float[] bounds(float[] values) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < values.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], values[i + a]);
                b[a + 3] = Math.max(b[a + 3], values[i + a]);
            }
        }
        return b;
    }

    /** Nearest system of self and ancestors is a rotor kind. */
    private static boolean spins(List<GltfReader.Node> all, GltfReader.Node node) {
        for (GltfReader.Node at = node; at != null; at = at.parent() < 0 ? null : all.get(at.parent())) {
            if (at.meta().has("system")) {
                String system = at.meta().get("system").getAsString();
                ComponentKind kind = system.equalsIgnoreCase("none") ? null : ComponentKind.byName(system);
                return kind == ComponentKind.ROTOR || kind == ComponentKind.TAIL_ROTOR;
            }
        }
        return false;
    }
}
