package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.google.gson.JsonObject;
import com.wf.combinedassault.ballistics.hit.GltfReader;
import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.ModelDataManager;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import com.wf.combinedassault.ballistics.hit.MountDefaults;
import com.wf.combinedassault.ballistics.hit.ModelRig;
import com.wf.combinedassault.ballistics.hit.NodeRole;
import com.wf.wflib.physics.api.ConvexHull;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class NodeMetaGameTest {

    private static final String TEMPLATE = "empty";

    /** Unit cube [0,1]^3 at (2,0,0) under a part frame at (1,1,0): 12 triangles, 8 corners. */
    private static String gltf() {
        return gltf("""
                [{"name":"hull","children":[1],"extras":{"combinedassaultRole":"body"}},
                 {"name":"turret","translation":[1,1,0],"children":[2,3],
                  "extras":{"combinedassaultRole":"part","combinedassaultInterface":"gun",
                            "combinedassault":{"system":"turret","health":40}}},
                 {"name":"cube","mesh":0,"translation":[1,-1,0],
                  "extras":{"combinedassaultRole":"collider","combinedassault":{"deck":true}}},
                 {"name":"elevation","translation":[0,0.5,0.5],
                  "extras":{"combinedassaultRole":"pitch","combinedassaultInterface":"gun"}}]""");
    }

    /** Every mesh node: the unit cube. */
    private static String gltf(String nodes) {
        float[] corners = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1};
        int[][] quads = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {2, 3, 7, 6}, {1, 2, 6, 5}, {0, 4, 7, 3}};
        ByteBuffer buffer = ByteBuffer.allocate(corners.length * 4 + 36 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float c : corners) {
            buffer.putFloat(c);
        }
        for (int[] q : quads) {
            buffer.putInt(q[0]).putInt(q[1]).putInt(q[2]).putInt(q[0]).putInt(q[2]).putInt(q[3]);
        }
        String data = Base64.getEncoder().encodeToString(buffer.array());
        return """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "buffers":[{"byteLength":%d,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":96},{"buffer":0,"byteOffset":96,"byteLength":144}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":8,"type":"VEC3"},
                              {"bufferView":1,"componentType":5125,"count":36,"type":"SCALAR"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
                 "nodes":%s}"""
                .formatted(buffer.capacity(), data, nodes);
    }

    private static GltfReader.Model read(String gltf) {
        try {
            return GltfReader.read(gltf.getBytes(StandardCharsets.UTF_8), (uri, location) -> new byte[0]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * wheel + tread replaced by wheel_hit; hit module under them kept; hull_box replaced by its collider ("hit").
     * No json centre, no socket => solid collider centroid.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void aHitSurfaceReplacesItsParentsSubtree(GameTestHelper helper) {
        GltfReader.Model model = read(gltf("""
                [{"name":"hull","children":[1,5],"extras":{"combinedassaultRole":"body"}},
                 {"name":"wheel","mesh":0,"children":[2,3,4]},
                 {"name":"tread","mesh":0,"translation":[0,2,0]},
                 {"name":"wheel_hit","mesh":0,"extras":{"combinedassaultRole":"hit_surface"}},
                 {"name":"engine","mesh":0,"extras":{"combinedassaultRole":"hit","combinedassault":{"system":"engine"}}},
                 {"name":"hull_box","mesh":0,"translation":[3,0,0],"children":[6]},
                 {"name":"hull_collider","mesh":0,
                  "extras":{"combinedassaultRole":"collider","combinedassault":{"hit":true}}}]"""));
        ModelRig rig = ModelRig.of("test", model.nodes());
        HitMesh hits = ModelDataManager.hitMesh("test", model, rig, Collections.nCopies(model.nodes().size(), null));
        StringBuilder got = new StringBuilder();
        for (HitMesh.Node node : hits.nodes()) {
            got.append(node.name()).append(node.module() ? "(module)" : "").append('=')
                    .append(node.mesh() == null ? 0 : node.mesh().triangleCount()).append(' ');
        }
        if (!got.toString().equals("hull=0 wheel=0 tread=0 wheel_hit=12 engine(module)=12 hull_box=0 hull_collider=12 ")) {
            helper.fail(got.toString());
            return;
        }
        List<ModelMounts.Link> links = model.nodes().stream()
                .map(n -> new ModelMounts.Link(n.name(), n.parent(), n.world())).toList();
        JsonObject vehicle = MountDefaults.apply("test", new JsonObject(), new ModelMounts(links, List.of(), List.of(), rig)).vehicle();
        String center = vehicle.getAsJsonObject("physics_info").get("center").toString();
        if (!center.equals("[3.5,0.5,0.5]")) {
            helper.fail("centre of mass " + center);
            return;
        }
        try {
            GltfReader.Model nested = read(gltf("""
                    [{"name":"hull","children":[1],"extras":{"combinedassaultRole":"body"}},
                     {"name":"wheel","mesh":0,"children":[2,3]},
                     {"name":"wheel_hit","mesh":0,"extras":{"combinedassaultRole":"hit_surface"}},
                     {"name":"tread","mesh":0,"children":[4]},
                     {"name":"tread_hit","mesh":0,"extras":{"combinedassaultRole":"hit_surface"}}]"""));
            ModelDataManager.hitMesh("test", nested, ModelRig.of("test", nested.nodes()),
                    Collections.nCopies(nested.nodes().size(), null));
            helper.fail("nested hit surfaces loaded");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void rolesBuildTheRig(GameTestHelper helper) {
        GltfReader.Model model;
        try {
            model = GltfReader.read(gltf().getBytes(StandardCharsets.UTF_8), (uri, location) -> new byte[0]);
        } catch (IOException e) {
            helper.fail("read: " + e);
            return;
        }
        ModelRig rig = ModelRig.of("test", model.nodes());
        ModelRig.Frame part = rig.frames().get(rig.part("gun"));
        ModelRig.Frame pitch = rig.frames().get(rig.pitch("gun"));
        if (rig.frames().size() != 3 || part.role() != NodeRole.PART || pitch.parent() != rig.part("gun")
                || !"turret".equals(model.nodes().get(1).meta().get("system").getAsString())) {
            helper.fail("frames " + rig.frames());
            return;
        }
        if (rig.colliders().size() != 1) {
            helper.fail("colliders " + rig.colliders().size());
            return;
        }
        ModelRig.Collider cube = rig.colliders().get(0);
        ConvexHull hull = cube.hull();
        if (cube.frame() != rig.part("gun") || !cube.deck() || !cube.solid() || hull.vertexCount() != 8
                || hull.faceCount() != 6 || Math.abs(hull.volume() - 1) > 1.0e-5f
                || Math.abs(hull.minX() - 2) > 1.0e-5f || Math.abs(hull.maxY() - 1) > 1.0e-5f) {
            helper.fail("collider frame " + cube.frame() + " deck " + cube.deck() + " vertices " + hull.vertexCount()
                    + " faces " + hull.faceCount() + " volume " + hull.volume() + " minX " + hull.minX());
            return;
        }
        helper.succeed();
    }
}
