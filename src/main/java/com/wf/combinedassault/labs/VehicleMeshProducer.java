package com.wf.combinedassault.labs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.TriangleBvh;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The hit geometry the resolver traces, per vehicle, in the lab frame at rest: {@code meshes/<ns>_<vehicle>.glb} in the
 * output dir, one glTF node per hit-model node (extras {@code part}, {@code kind} surface|module|disc), non-indexed
 * triangles, vertex attribute {@code _ARMOR} = declared normal thickness (KE mm) of the triangle's plate (0 = unbound). The table lists each file
 * with its SHA-256.
 */
public final class VehicleMeshProducer implements LabProducer {

    static final LabTable TABLE = LabTable.of("combinedassault:vehicle_meshes")
            .dimStr("vehicle").measureStr("file").measureStr("sha256")
            .measure("nodes", "").measure("tris", "").measure("bytes", "")
            .build();

    private static final int DISC_SEGMENTS = 24;
    private static final int FLOAT = 5126;

    @Override
    public String domain() {
        return "vehicle";
    }

    @Override
    public String id() {
        return TABLE.name();
    }

    @Override
    public LabTable table() {
        return TABLE;
    }

    @Override
    public void produce(LabContext ctx, LabSink sink) {
        Path dir = ctx.outputDir().resolve("meshes");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (VehicleRig rig : VehicleProducer.rigs(ctx)) {
            String name = rig.id.getNamespace() + "_" + rig.id.getPath().replace('/', '_') + ".glb";
            int[] stats = new int[2];
            byte[] glb = glb(rig, stats);
            try {
                Files.write(dir.resolve(name), glb);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sink.row(rig.id.toString(), name, sha256(glb), stats[0], stats[1], glb.length);
            rig.vehicle.discard();
        }
    }

    private static byte[] glb(VehicleRig rig, int[] stats) {
        JsonArray nodes = new JsonArray(), meshes = new JsonArray(), accessors = new JsonArray(), views = new JsonArray();
        java.io.ByteArrayOutputStream bin = new java.io.ByteArrayOutputStream();
        int tris = 0;
        for (int i = 0; i < rig.hits.model().nodes().size(); i++) {
            HitModel.Node n = rig.hits.model().nodes().get(i);
            HitMesh.Node mesh = n.mesh();
            float[] t;
            float[] mm;
            String kind;
            if (mesh.disc() != null) {
                t = disc(mesh.disc());
                mm = new float[t.length / 9];
                kind = "disc";
            } else if (mesh.mesh() != null) {
                TriangleBvh bvh = mesh.mesh();
                t = bvh.triangles();
                mm = new float[bvh.triangleCount()];
                HullProfile profile = rig.vehicle.armorProfile;
                for (int k = 0; k < mm.length; k++) {
                    String id = rig.hits.model().mesh().plateId(bvh.plate(k));
                    mm[k] = id == null ? 0 : profile.plates.get(id).nominal(false);
                }
                kind = mesh.module() ? "module" : "surface";
            } else {
                continue;
            }
            Matrix4f pose = rig.hits.pose(i, rig.vehicle.position(), new Matrix4f());
            ByteBuffer pos = ByteBuffer.allocate(t.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer arm = ByteBuffer.allocate(t.length / 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            Vector3f v = new Vector3f();
            for (int k = 0; k < t.length / 3; k++) {
                pose.transformPosition(v.set(t[3 * k], t[3 * k + 1], t[3 * k + 2]));
                for (int a = 0; a < 3; a++) {
                    pos.putFloat(v.get(a));
                    lo[a] = Math.min(lo[a], v.get(a));
                    hi[a] = Math.max(hi[a], v.get(a));
                }
                arm.putFloat(mm[k / 3]);
            }
            int posView = view(views, bin, pos.array());
            int armView = view(views, bin, arm.array());
            int posAcc = accessor(accessors, posView, t.length / 3, "VEC3", lo, hi);
            int armAcc = accessor(accessors, armView, t.length / 3, "SCALAR", null, null);
            JsonObject attrs = new JsonObject();
            attrs.addProperty("POSITION", posAcc);
            attrs.addProperty("_ARMOR", armAcc);
            JsonObject prim = new JsonObject();
            prim.add("attributes", attrs);
            prim.addProperty("mode", 4);
            JsonArray prims = new JsonArray();
            prims.add(prim);
            JsonObject m = new JsonObject();
            m.add("primitives", prims);
            meshes.add(m);
            JsonObject extras = new JsonObject();
            extras.addProperty("part", n.part() == null ? "" : n.part());
            extras.addProperty("kind", kind);

            JsonObject node = new JsonObject();
            node.addProperty("name", mesh.name());
            node.addProperty("mesh", meshes.size() - 1);
            node.add("extras", extras);
            nodes.add(node);
            tris += t.length / 9;
        }
        stats[0] = nodes.size();
        stats[1] = tris;
        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "wflabs combinedassault " + rig.id);
        root.add("asset", asset);
        JsonArray all = new JsonArray();
        for (int i = 0; i < nodes.size(); i++) {
            all.add(i);
        }
        JsonObject scene = new JsonObject();
        scene.add("nodes", all);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        root.add("scenes", scenes);
        root.addProperty("scene", 0);
        root.add("nodes", nodes);
        root.add("meshes", meshes);
        root.add("accessors", accessors);
        root.add("bufferViews", views);
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", bin.size());
        JsonArray buffers = new JsonArray();
        buffers.add(buffer);
        root.add("buffers", buffers);
        byte[] json = pad(root.toString().getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] data = pad(bin.toByteArray(), (byte) 0);
        ByteBuffer out = ByteBuffer.allocate(12 + 8 + json.length + 8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
        out.putInt(json.length).putInt(0x4E4F534A).put(json);
        out.putInt(data.length).putInt(0x004E4942).put(data);
        return out.array();
    }

    private static int view(JsonArray views, java.io.ByteArrayOutputStream bin, byte[] bytes) {
        while (bin.size() % 4 != 0) {
            bin.write(0);
        }
        JsonObject v = new JsonObject();
        v.addProperty("buffer", 0);
        v.addProperty("byteOffset", bin.size());
        v.addProperty("byteLength", bytes.length);
        bin.writeBytes(bytes);
        views.add(v);
        return views.size() - 1;
    }

    private static int accessor(JsonArray accessors, int view, int count, String type, float[] min, float[] max) {
        JsonObject a = new JsonObject();
        a.addProperty("bufferView", view);
        a.addProperty("componentType", FLOAT);
        a.addProperty("count", count);
        a.addProperty("type", type);
        if (min != null) {
            JsonArray lo = new JsonArray(), hi = new JsonArray();
            for (int i = 0; i < 3; i++) {
                lo.add(min[i]);
                hi.add(max[i]);
            }
            a.add("min", lo);
            a.add("max", hi);
        }
        accessors.add(a);
        return accessors.size() - 1;
    }

    private static byte[] pad(byte[] in, byte fill) {
        int n = (in.length + 3) & ~3;
        byte[] out = java.util.Arrays.copyOf(in, n);
        java.util.Arrays.fill(out, in.length, n, fill);
        return out;
    }

    private static float[] disc(HitMesh.Disc d) {
        int a1 = (d.axis() + 1) % 3, a2 = (d.axis() + 2) % 3;
        float[] out = new float[DISC_SEGMENTS * 2 * 9];
        int o = 0;
        for (int s = 0; s < DISC_SEGMENTS; s++) {
            float[][] ring = new float[4][3];
            for (int e = 0; e < 2; e++) {
                double ang = 2 * Math.PI * (s + e) / DISC_SEGMENTS;
                for (int side = 0; side < 2; side++) {
                    float[] p = ring[e * 2 + side];
                    p[d.axis()] = side == 0 ? d.lo() : d.hi();
                    p[a1] = (float) (Math.cos(ang) * d.radius());
                    p[a2] = (float) (Math.sin(ang) * d.radius());
                }
            }
            int[][] tri = {{0, 1, 2}, {1, 3, 2}};
            for (int[] tr : tri) {
                for (int k : tr) {
                    System.arraycopy(ring[k], 0, out, o, 3);
                    o += 3;
                }
            }
        }
        return out;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
