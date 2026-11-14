package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.TriangleBvh;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.wflib.armor.api.hull.HullResolver;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plate under the crosshair == bound id: every plate a vehicle declares is probed at faces the mesh binds to it, rays
 * from outside along the face normal, through {@link VehicleHits#surface} and {@link HullResolver#plate}. A vehicle
 * with no {@code armor.plates} checks nothing.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class PlateRegionsGameTest {

    private static final String TEMPLATE = "empty";
    private static final String BATCH = "plateregions";
    private static final int LANE_Z = -3000, LANE_PITCH = -64, FLOOR_X = 6, FLOOR_Z = 8;
    private static final int SAMPLES = 8;
    private static final float REACHED = 0.03f;
    private static final double STANDOFF = 3.0;

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void humveePlatesAreWhereTheMeshBindsThem(GameTestHelper helper) {
        probe(helper, 0, "humvee");
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void m113PlatesAreWhereTheMeshBindsThem(GameTestHelper helper) {
        probe(helper, 1, "m113");
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void m1a2PlatesAreWhereTheMeshBindsThem(GameTestHelper helper) {
        probe(helper, 2, "m1a2");
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void t90PlatesAreWhereTheMeshBindsThem(GameTestHelper helper) {
        probe(helper, 3, "t90");
    }

    private static void probe(GameTestHelper helper, int lane, String vehicle) {
        ResourceLocation id = CombinedAssault.modLocation(vehicle);
        TestLanes.run(helper, LANE_Z + lane * LANE_PITCH, FLOOR_X, FLOOR_Z, id, (seq, v) -> seq
                .thenExecute(() -> check(helper, v.get())), at -> { });
    }

    private static void check(GameTestHelper helper, AbstractVehicle vehicle) {
        var profile = vehicle.armorProfile;
        VehicleHits hits = vehicle.hits();
        if (profile == null || profile.plates.isEmpty() || hits == null) {
            return;
        }
        Map<String, Integer> reached = new HashMap<>();
        for (String id : profile.plates.keySet()) {
            reached.put(id, 0);
        }
        Matrix4f pose = new Matrix4f();
        Vector3f n = new Vector3f();
        float[] normal = new float[3];
        for (int i = 0; i < hits.model().nodes().size(); i++) {
            HitModel.Node node = hits.model().nodes().get(i);
            TriangleBvh bvh = node.mesh().mesh();
            if (bvh == null || node.mesh().module() || !hits.live(i)) {
                continue;
            }
            hits.pose(i, vehicle.position(), pose);
            float[] t = bvh.triangles();
            for (int tri = 0; tri < bvh.triangleCount(); tri++) {
                String plate = hits.model().mesh().plateId(bvh.plate(tri));
                if (plate == null || reached.get(plate) >= SAMPLES) {
                    continue;
                }
                int a = tri * 9;
                Vector3f c = pose.transformPosition(new Vector3f((t[a] + t[a + 3] + t[a + 6]) / 3,
                        (t[a + 1] + t[a + 4] + t[a + 7]) / 3, (t[a + 2] + t[a + 5] + t[a + 8]) / 3));
                bvh.normal(tri, normal);
                pose.transformDirection(n.set(normal[0], normal[1], normal[2])).normalize();
                Vec3 centre = vehicle.position().add(c.x, c.y, c.z);
                for (int side = -1; side <= 1; side += 2) {
                    Vec3 dir = new Vec3(n.x, n.y, n.z).scale(-side);
                    Vec3 from = centre.subtract(dir.scale(STANDOFF));
                    VehicleHits.Hit hit = hits.surface(from, centre.add(dir.scale(0.05)));
                    if (hit == null || hit.point().distanceTo(centre) > REACHED) {
                        continue;
                    }
                    HullResolver.Plate resolved = HullResolver.plate(vehicle, profile, hit.node(), hit.point(),
                            hit.normal(), hit.plate(), dir, false);
                    if (!plate.equals(hit.plate()) || resolved == null || !plate.equals(resolved.plateId())) {
                        helper.fail(String.format(Locale.ROOT, "%s: triangle %d of %s bound to '%s' read '%s' / resolver '%s'; "
                                        + "centre %.3f %.3f %.3f, struck node %s",
                                vehicle.getType(), tri, node.mesh().name(), plate, hit.plate(),
                                resolved == null ? null : resolved.plateId(), c.x, c.y, c.z,
                                hits.model().nodes().get(hit.node()).mesh().name()));
                        return;
                    }
                    reached.merge(plate, 1, Integer::sum);
                    break;
                }
            }
        }
        reached.forEach((plate, count) -> helper.assertTrue(count > 0, "plate '" + plate
                + "': no bound face reachable from outside"));
    }
}
