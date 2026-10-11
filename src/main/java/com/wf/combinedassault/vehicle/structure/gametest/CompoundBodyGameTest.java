package com.wf.combinedassault.vehicle.structure.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.vehicle.collision.BoxBuffer;
import com.wf.combinedassault.vehicle.collision.CompoundBody;
import com.wf.combinedassault.vehicle.collision.ContactSynthesis;
import com.wf.combinedassault.vehicle.collision.SweptHull;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;

/**
 * A vehicle colliding as every piece it is, rather than as the one the solve is built around.
 *
 * <p>The physics hull is the largest piece a pack declares, and for a long time it was the only one
 * that met the world: a gun barrel out past the nose went through walls, and a turret above the deck
 * had nothing under it. This is the test of the other pieces being there.
 *
 * <p>It is also the test of them not being there twice. A pack is told to draw detail pieces inside
 * the silhouette so that tagging them changes no geometry, and a piece drawn inside the hull adds
 * nothing to the outside of the vehicle, which is the only surface a collision can happen on. Such a
 * piece is dropped rather than solved against, and this asserts which of the tank's pieces are which.
 *
 * <p>Nothing here writes to the world. Arenas share one level eight blocks apart, so the geometry is
 * handed to the collision code directly instead of being built out of blocks.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class CompoundBodyGameTest {

    private static final String TEMPLATE = "empty";

    private static final ResourceLocation HULLED = CombinedAssault.modLocation("m1a2");

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theTankCollidesAsMoreThanItsHull(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        CompoundBody compound = vehicle.collisionBody();
        if (!compound.hasPieces()) {
            helper.fail("the tank collides as one piece, so the turret and the gun meet nothing");
            return;
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < compound.pieceCount(); i++) {
            names.add(compound.piece(i).tag().name());
        }
        if (!names.contains("turret") || !names.contains("main gun")) {
            helper.fail("the tank's collision pieces are " + names
                    + ", which is missing the turret or the gun");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aPieceInsideTheSilhouetteIsNotSolvedAgainst(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        CompoundBody compound = vehicle.collisionBody();
        for (int i = 0; i < compound.pieceCount(); i++) {
            String name = compound.piece(i).tag().name();
            // All three are drawn inside the hull, so the hull already is their outside.
            if (name.equals("left track") || name.equals("right track") || name.equals("engine deck")) {
                helper.fail("the tank solves against its " + name
                        + ", which is drawn inside the hull and can only duplicate it");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theGunBarrelMeetsAWallTheHullMisses(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        OBB pose = cube.obb();
        float extentY = pose.extents().y;
        double skirt = vehicle.sweepSkirt(pose.rotation());
        OBB climb = SweptHull.climbHull(pose, skirt,
                new OBB(new Vector3f(), new Vector3f(), new Quaternionf()));

        VehicleCubeOBB gun = pieceNamed(vehicle, "main gun");
        if (gun == null) {
            helper.fail("the tank has no gun barrel among its collision pieces");
            return;
        }
        // Spawned facing +Z with no pitch or roll, so the vehicle's forward axis is the world's and
        // the nose of each piece is its own centre plus its own depth.
        double tip = gun.obb().centerZ() + gun.obb().extents().z;
        double front = pose.centerZ() + pose.extents().z;
        double gap = 0.5;
        // Standing off the barrel's tip, so nothing starts inside it, and a step longer than the
        // gap but far shorter than the barrel's overhang: the barrel has to meet this, the hull
        // cannot possibly.
        double wall = tip + gap;
        double move = gap + 0.2;
        if (front + move >= wall) {
            helper.fail("the hull itself reaches the wall over this step, so it proves nothing");
            return;
        }
        BoxBuffer boxes = new BoxBuffer();
        boxes.add(pose.centerX() - 2, pose.centerY(), wall,
                pose.centerX() + 2, pose.centerY() + 2, wall + 1);

        double hullAlone = SweptHull.timeOfImpact(climb, boxes, 0, 0, move, null);
        if (hullAlone < 1.0) {
            helper.fail("the hull alone stopped at " + hullAlone + " for a wall it cannot reach");
            return;
        }
        double compound = vehicle.collisionBody().timeOfImpact(climb,
                SweptHull.climbRise(extentY, skirt), SweptHull.climbBottom(extentY, skirt),
                boxes, 0, 0, move, hullAlone);
        if (compound >= 1.0) {
            helper.fail("the tank drove its gun barrel straight through a wall");
            return;
        }
        // And it stopped where the barrel met it, not somewhere arbitrary earlier.
        double expected = gap / move;
        if (Math.abs(compound - expected) > 0.05) {
            helper.fail("the barrel met the wall at " + compound + " of the step, where the"
                    + " geometry puts it at " + expected);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aContactOnTheTurretComesBackInTheHullsFrame(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        OBB pose = cube.obb();
        // Against the turret's flank, well above the hull's own roof.
        double top = pose.centerY() + pose.extents().y;
        BoxBuffer boxes = new BoxBuffer();
        boxes.add(pose.centerX() + 1.5, top - 0.1, pose.centerZ() - 1,
                pose.centerX() + 3.5, top + 1.0, pose.centerZ() + 1);

        List<VehicleCubeOBB.CubePoint> out = new ArrayList<>();
        vehicle.collisionBody().collect(pose, boxes, (point, world, box) -> true, null, out,
                cube.climbSkirt());
        if (out.isEmpty()) {
            helper.fail("a wall against the turret's flank made no contacts");
            return;
        }
        for (VehicleCubeOBB.CubePoint point : out) {
            Vector3f local = point.obbLocalPos();
            // In the hull's frame, which is what everything downstream measures against: above the
            // hull's own roof, not around zero as it would be in the turret's own.
            if (local.y < pose.extents().y - 0.5f) {
                helper.fail("a contact on the turret came back at local y " + local.y
                        + ", which is inside the hull, so it was never put into the hull's frame");
                return;
            }
            if (Math.abs(point.normal().length() - 1) > 1.0e-3) {
                helper.fail("a contact carries a normal of length " + point.normal().length());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aTurnedVehiclePosesItsPiecesWhereTheyReallyAre(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        // Yaw, pitch and roll together, because a piece's pose in the hull's frame is composed out
        // of two cubes' local rotations and any of the three could be composed the wrong way round.
        vehicle.setYRot(37);
        vehicle.setXRot(11);
        vehicle.setZRot(-6);
        vehicle.updateOBBs();

        VehicleCubeOBB turret = pieceNamed(vehicle, "turret");
        if (turret == null) {
            helper.fail("the tank has no turret among its collision pieces");
            return;
        }
        // Against the turret's flank, along the turret's own port axis rather than the world's, so
        // the block is where the turret is rather than where an unturned tank's turret would be.
        OBB live = turret.obb();
        Vector3f port = live.getAxes()[0];
        double half = 1.0;
        double reach = live.extents().x + half - 0.35;
        double x = live.centerX() + port.x * reach;
        double y = live.centerY() + port.y * reach;
        double z = live.centerZ() + port.z * reach;
        BoxBuffer boxes = new BoxBuffer();
        boxes.add(x - half, y - half, z - half, x + half, y + half, z + half);

        List<VehicleCubeOBB.CubePoint> out = new ArrayList<>();
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        vehicle.collisionBody().collect(cube.obb(), boxes, (point, world, box) -> true, null, out,
                cube.climbSkirt());
        if (out.isEmpty()) {
            helper.fail("a block against the turned turret's flank reached no piece, so a turned"
                    + " vehicle poses its pieces somewhere they are not");
            return;
        }
        OBB around = live.inflate(0.4F);
        for (VehicleCubeOBB.CubePoint point : out) {
            Vec3 at = new Vec3(point.worldX(), point.worldY(), point.worldZ());
            if (!around.contains(at)) {
                helper.fail("a contact on the turret came out at " + at + ", and the turret is at "
                        + live.center() + ": the piece is posed in the wrong frame");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void groundUnderTheTankTouchesTheHullAlone(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        OBB pose = cube.obb();
        Vector3f[] axes = pose.getAxes();
        double floor = pose.centerY() - pose.extents().y;
        BoxBuffer ground = new BoxBuffer();
        ground.add(pose.centerX() - 6, floor - 1, pose.centerZ() - 8,
                pose.centerX() + 6, floor, pose.centerZ() + 8);

        List<VehicleCubeOBB.CubePoint> fromHull = new ArrayList<>();
        ContactSynthesis.collect(cube, pose, axes, ground, (point, world, box) -> true, null,
                fromHull, cube.climbSkirt(), vehicle.collisionBody().hullFilter());
        if (fromHull.isEmpty()) {
            helper.fail("a tank standing on open ground made no contacts at all");
            return;
        }
        List<VehicleCubeOBB.CubePoint> fromPieces = new ArrayList<>();
        vehicle.collisionBody().collect(pose, ground, (point, world, box) -> true, null,
                fromPieces, cube.climbSkirt());
        if (!fromPieces.isEmpty()) {
            helper.fail("ground under the tank reached " + fromPieces.size()
                    + " contacts on a turret or a gun barrel");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theCompoundReachesFurtherThanTheHull(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        // The substep count is sized so nothing travels more than half a block a slice, and the
        // point that travels furthest under a spin is the furthest out. On the tank that is the
        // muzzle, and a hull that thinks it ends at its own nose slices too coarsely for it.
        float hull = vehicle.getMainCubeOBB().obb().extents().length();
        float compound = vehicle.collisionBody().reachRadius();
        if (compound <= hull) {
            helper.fail("the tank's compound reaches " + compound + " and its hull " + hull
                    + ", so the gun barrel is not being counted");
            return;
        }
        // Conservative by construction, because it takes the corner of a bound rather than the
        // farthest point of a shape, but a runaway here would slice every tick sixteen ways.
        if (compound > hull * 3) {
            helper.fail("the compound claims a reach of " + compound + " against a hull radius of "
                    + hull + ", which would slice every tick to nothing");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aSampleInsideAnotherPieceIsNotAContact(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        ContactSynthesis.ContactFilter filter = vehicle.collisionBody().hullFilter();
        if (filter == null) {
            helper.fail("the tank has no compound, so there is nothing to filter against");
            return;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        VehicleCubeOBB turret = pieceNamed(vehicle, "turret");
        if (turret == null) {
            helper.fail("the tank has no turret piece to test against");
            return;
        }
        // The turret's own centre, said in the hull's frame. Nothing is more inside it than that.
        Vector3f inside = new Vector3f(turret.localCenter()).sub(cube.localCenter());
        if (filter.keep(point(cube, inside))) {
            helper.fail("a sample at the middle of the turret was kept as a contact on the hull");
            return;
        }
        // And a point out past every piece is still a contact.
        Vector3f outside = new Vector3f(0, cube.obb().extents().y + 8, 0);
        if (!filter.keep(point(cube, outside))) {
            helper.fail("a sample well clear of the vehicle was thrown away");
            return;
        }
        helper.succeed();
    }

    @Nullable
    private static VehicleCubeOBB pieceNamed(AbstractVehicle vehicle, String name) {
        CompoundBody compound = vehicle.collisionBody();
        for (int i = 0; i < compound.pieceCount(); i++) {
            if (name.equals(compound.piece(i).tag().name())) {
                return compound.piece(i);
            }
        }
        return null;
    }

    private static VehicleCubeOBB.CubePoint point(VehicleCubeOBB cube, Vector3f at) {
        return new VehicleCubeOBB.CubePoint(cube, new Vector3f(at), VehicleCubeOBB.CubeFace.TOP);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
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
