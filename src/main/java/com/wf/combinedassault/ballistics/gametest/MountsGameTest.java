package com.wf.combinedassault.ballistics.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import com.wf.combinedassault.ballistics.hit.MountDefaults;
import com.wf.combinedassault.ballistics.hit.GltfReader;
import com.wf.combinedassault.ballistics.hit.ModelRig;
import com.wf.combinedassault.ballistics.hit.NodeRole;

import java.util.List;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class MountsGameTest {

    private static final String TEMPLATE = "empty";

    private static GltfReader.Node node(String name, int parent, Matrix4f world, String role, String iface) {
        return new GltfReader.Node(name, parent, world, world, new float[0], new int[0], new int[0], role, iface, new JsonObject());
    }

    /** Seat facing port on a turret frame, a 1 x 0.5 panel facing aft on the body; the def keeps its own seat offset. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aMarkerFillsWhatTheJsonLeavesOut(GameTestHelper helper) {
        Matrix4f turret = new Matrix4f().translation(0, 2, 0);
        Matrix4f seat = new Matrix4f().translation(0.5f, 3, 0).rotateY((float) Math.toRadians(90));
        Matrix4f panel = new Matrix4f().translation(0, 1, -3);
        float[] quad = {0.5f, 0.75f, -3, -0.5f, 0.75f, -3, -0.5f, 1.25f, -3, 0.5f, 0.75f, -3, -0.5f, 1.25f, -3, 0.5f, 1.25f, -3};
        List<GltfReader.Node> nodes = List.of(
                node("root", -1, new Matrix4f(), "body", ""),
                node("turret_node", 0, turret, "part", "turret"),
                node("socket_seat_turret", 1, seat, "seat", "turret"),
                node("socket_era_aft", 0, panel, "era", "aft"));
        ModelRig rig = ModelRig.of("test", nodes);
        List<ModelMounts.Link> links = nodes.stream().map(n -> new ModelMounts.Link(n.name(), n.parent(), n.world())).toList();
        List<ModelMounts.Mount> mounts = List.of(
                new ModelMounts.Mount(2, "socket_seat_turret", NodeRole.SEAT, "turret", -1, seat.getTranslation(new Vector3f()),
                        seat.getUnnormalizedRotation(new Quaternionf()), new Vector3f(1), new float[0], null),
                new ModelMounts.Mount(3, "socket_era_aft", NodeRole.ERA, "aft", -1, panel.getTranslation(new Vector3f()),
                        new Quaternionf(), new Vector3f(1), quad, null));
        JsonObject vehicle = JsonParser.parseString("""
                {"parts": [{"id": "turret", "type": "combinedassault:weapon", "seat_offset": [9, 9, 9]}]}""")
                .getAsJsonObject();

        MountDefaults.Result result = MountDefaults.apply("test", vehicle, new ModelMounts(links, mounts, List.of(), rig));
        JsonObject part = result.vehicle().getAsJsonArray("parts").get(0).getAsJsonObject();
        JsonObject era = result.vehicle().getAsJsonArray("addon_armor").get(0).getAsJsonObject();
        String got = part.get("seat_offset") + " rot " + part.get("seat_rot") + ", era " + era;
        if (!part.get("seat_offset").toString().equals("[9,9,9]") || !result.overridden().contains("turret.seat_offset")
                || Math.abs(part.get("seat_rot").getAsFloat() + 90) > 1.0e-3
                || !era.get("normal").toString().equals("[0.0,0.0,-1.0]") || !era.get("up").toString().equals("[0.0,1.0,0.0]")
                || Math.abs(era.get("width").getAsFloat() - 1) > 1.0e-4 || Math.abs(era.get("height").getAsFloat() - 0.5f) > 1.0e-4
                || !era.get("center").toString().equals("[0.0,1.0,-3.0]") || !era.get("bone").getAsString().equals("root")
                || !era.get("kind").getAsString().equals("era")) {
            helper.fail(got);
            return;
        }
        helper.succeed();
    }

    /**
     * Mass, pivot, a track face (fan of a square: outline = its four corners in winding order), a suspension frame's
     * render bone, pitch frame elevation limits.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aModelFillsMassPivotTrackAndLimits(GameTestHelper helper) {
        JsonObject limits = JsonParser.parseString("{\"x_rot_min\": -10, \"x_rot_max\": 60}").getAsJsonObject();
        List<GltfReader.Node> nodes = List.of(
                node("root", -1, new Matrix4f(), "body", ""),
                node("wheel_node", 0, new Matrix4f(), "part", "wheel"),
                node("gun_node", 0, new Matrix4f(), "part", "gun"),
                new GltfReader.Node("gun_pitch", 2, new Matrix4f(), new Matrix4f(), new float[0], new int[0], new int[0],
                        "pitch", "gun", limits),
                node("mass", 0, new Matrix4f(), "center_of_mass", ""),
                node("pivot", 0, new Matrix4f(), "pivot", ""),
                node("track_left", 0, new Matrix4f(), "track", "track_l"));
        ModelRig rig = ModelRig.of("test", nodes);
        List<ModelMounts.Link> links = nodes.stream().map(n -> new ModelMounts.Link(n.name(), n.parent(), n.world())).toList();
        float[] fan = {1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0};
        List<ModelMounts.Mount> mounts = List.of(
                new ModelMounts.Mount(4, "mass", NodeRole.CENTER_OF_MASS, "", -1, new Vector3f(0, 1, 0.5f),
                        new Quaternionf(), new Vector3f(1), new float[0], null),
                new ModelMounts.Mount(5, "pivot", NodeRole.PIVOT, "", -1, new Vector3f(0, 2, 0),
                        new Quaternionf(), new Vector3f(1), new float[0], null),
                new ModelMounts.Mount(6, "track_left", NodeRole.TRACK, "track_l", -1, new Vector3f(),
                        new Quaternionf(), new Vector3f(1), fan, null));
        JsonObject vehicle = JsonParser.parseString("""
                {"parts": [{"id": "wheel", "type": "combinedassault:suspension"},
                           {"id": "gun", "type": "combinedassault:weapon"},
                           {"id": "track_l", "type": "combinedassault:track"}]}""").getAsJsonObject();

        List<ModelMounts.Wheel> wheels = List.of(new ModelMounts.Wheel("road_0", new Vector3f(1, 0.3f, 0.5f)));
        JsonObject out = MountDefaults.apply("test", vehicle, new ModelMounts(links, mounts, wheels, rig)).vehicle();
        var parts = out.getAsJsonArray("parts");
        String got = out.getAsJsonObject("physics_info").get("center") + " " + out.get("center_offset") + " "
                + parts.get(0).getAsJsonObject().get("render_bone") + " " + parts.get(1).getAsJsonObject().get("rot_info")
                + " " + parts.get(1).getAsJsonObject().get("render_bone") + " " + parts.get(2).getAsJsonObject().get("tracks")
                + " " + parts.get(2).getAsJsonObject().get("road_wheels");
        if (!got.equals("[0.0,1.0,0.5] [0.0,2.0,0.0] \"wheel_node\" {\"x_rot_min\":-10.0,\"x_rot_max\":60.0} null "
                + "[[[1.0,0.0,0.0],[1.0,0.0,1.0],[1.0,1.0,1.0],[1.0,1.0,0.0]]] "
                + "[{\"node\":\"road_0\",\"track\":0,\"center\":[1.0,0.3,0.5]}]")) {
            helper.fail(got);
            return;
        }
        helper.succeed();
    }
}
