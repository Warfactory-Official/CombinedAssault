package com.wf.combinedassault.vehicle.structure.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.collision.ContactSynthesis;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;

/**
 * What the contact solve actually makes of a hull.
 *
 * <p>The point of drawing a sloped plate is that a contact on it carries the plate's own normal, so
 * the impulse pushes along the slope rather than square into a body axis. Nothing else in the mod
 * would notice that going wrong: the vehicle would still collide, still stop, still rest, and only
 * look subtly wrong while doing it. So it is asserted directly.
 *
 * <p>These were written to answer a measurement rather than a guess: before the face selection was
 * fixed, this path produced zero contacts, and every other test still passed.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HullContactProbeGameTest {

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSlopedPlateGivesASlopedContact(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        if (cube == null || !cube.hasHull()) {
            helper.fail("the tank's physics cube has no hull, so there is nothing to test");
            return;
        }
        OBB pose = cube.obb();
        Vector3f[] axes = pose.getAxes();
        double floor = pose.centerY() - pose.extents().y;

        // Driven into a wall, which is where the glacis is.
        List<AABB> boxes = new ArrayList<>();
        double front = pose.centerZ() + pose.extents().z - 0.4;
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 2; y++) {
                boxes.add(new AABB(pose.centerX() + x, floor + y, front,
                        pose.centerX() + x + 1, floor + y + 1, front + 1));
            }
        }
        List<VehicleCubeOBB.CubePoint> out = new ArrayList<>();
        ContactSynthesis.collect(cube, pose, axes, boxes, (point, world, box) -> true, out);
        if (out.isEmpty()) {
            helper.fail("a wall driven into the front of the tank made no contacts at all");
            return;
        }
        boolean sloped = false;
        for (VehicleCubeOBB.CubePoint point : out) {
            Vector3f normal = point.normal();
            if (Math.abs(normal.length() - 1) > 1.0e-3) {
                helper.fail("a contact carries a normal of length " + normal.length());
                return;
            }
            // Hull face normal, not a body axis: > 2 deg off every axis. Lower plate sits in the climb skirt.
            if (Math.max(Math.abs(normal.x), Math.max(Math.abs(normal.y), Math.abs(normal.z))) < 0.9994f) {
                sloped = true;
            }
        }
        if (!sloped) {
            helper.fail("the tank met a wall with its glacis and every contact came back square: "
                    + out.get(0).normal());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aFlatFloorGivesContactsOnTheBelly(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        if (cube == null || !cube.hasHull()) {
            helper.fail("the tank's physics cube has no hull, so there is nothing to test");
            return;
        }
        OBB pose = cube.obb();
        Vector3f[] axes = pose.getAxes();
        double floor = pose.centerY() - pose.extents().y;

        // One merged slab, which is what the cache hands the solve for open ground.
        List<AABB> boxes = List.of(new AABB(pose.centerX() - 4, floor - 1, pose.centerZ() - 6,
                pose.centerX() + 4, floor, pose.centerZ() + 6));
        List<VehicleCubeOBB.CubePoint> out = new ArrayList<>();
        ContactSynthesis.collect(cube, pose, axes, boxes, (point, world, box) -> true, out);
        if (out.size() < 4) {
            helper.fail("a tank standing on open ground made " + out.size() + " contacts");
            return;
        }
        for (VehicleCubeOBB.CubePoint point : out) {
            if (point.cubeFace() != VehicleCubeOBB.CubeFace.BOTTOM || point.normal().y > -0.9f) {
                helper.fail("a contact with the ground came back on " + point.cubeFace()
                        + " facing " + point.normal());
                return;
            }
        }
        // And the same ground cut into blocks has to say the same thing.
        List<AABB> blocks = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -5; z <= 5; z++) {
                blocks.add(new AABB(pose.centerX() + x, floor - 1, pose.centerZ() + z,
                        pose.centerX() + x + 1, floor, pose.centerZ() + z + 1));
            }
        }
        out.clear();
        ContactSynthesis.collect(cube, pose, axes, blocks, (point, world, box) -> true, out);
        if (out.isEmpty()) {
            helper.fail("the same ground cut into blocks made no contacts");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void thesameGeometryFarFromSpawnGivesTheSameContacts(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        if (cube == null || !cube.hasHull()) {
            helper.fail("the tank's physics cube has no hull, so there is nothing to test");
            return;
        }
        // Past 2^23 a float ordinate lands on a grid two blocks wide, which is wider than the whole
        // contact margin. Anything on this path that narrows a world coordinate before subtracting
        // it shows up here as a different answer for the same geometry.
        List<VehicleCubeOBB.CubePoint> near = contactsAt(cube, 0.5, 60.5, 0.5);
        List<VehicleCubeOBB.CubePoint> far = contactsAt(cube, 12_000_000.5, 60.5, -2_400_000.5);
        if (near.isEmpty()) {
            helper.fail("the near case made no contacts, so this compares nothing");
            return;
        }
        if (near.size() != far.size()) {
            helper.fail("the same tank on the same ground made " + near.size()
                    + " contacts near spawn and " + far.size() + " twelve million blocks out");
            return;
        }
        for (int i = 0; i < near.size(); i++) {
            Vector3f a = near.get(i).obbLocalPos();
            Vector3f b = far.get(i).obbLocalPos();
            if (a.distance(b) > 1.0e-3f) {
                helper.fail("contact " + i + " sits at " + a + " near spawn and " + b + " far out");
                return;
            }
        }
        helper.succeed();
    }

    /** The tank's own shape and pose, moved to a given place, standing on a slab with a kerb. */
    private static List<VehicleCubeOBB.CubePoint> contactsAt(VehicleCubeOBB cube,
                                                             double x, double y, double z) {
        OBB pose = new OBB(new Vector3f(), new Vector3f(cube.obb().extents()),
                new Quaternionf(cube.obb().rotation()));
        pose.setCenter(x, y, z);
        Vector3f[] axes = pose.getAxes();
        double floor = y - pose.extents().y;
        List<AABB> boxes = new ArrayList<>();
        for (int bx = -4; bx <= 4; bx++) {
            for (int bz = -6; bz <= 6; bz++) {
                boxes.add(new AABB(x + bx, floor - 1, z + bz, x + bx + 1, floor, z + bz + 1));
            }
        }
        for (int bx = -4; bx <= 4; bx++) {
            boxes.add(new AABB(x + bx, floor, z + 4.0, x + bx + 1, floor + 0.5, z + 5.0));
        }
        List<VehicleCubeOBB.CubePoint> out = new ArrayList<>();
        ContactSynthesis.collect(cube, pose, axes, boxes, (point, world, box) -> true, out);
        return out;
    }

    private static AbstractVehicle spawn(GameTestHelper helper) {
        var id = CombinedAssault.modLocation("m1a2");
        var data = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElse(null);
        if (data == null) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 4, 1));
        AbstractVehicle vehicle = data.construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        vehicle.updateOBBs();
        return vehicle;
    }

}
