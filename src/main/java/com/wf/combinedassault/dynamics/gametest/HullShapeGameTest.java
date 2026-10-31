package com.wf.combinedassault.dynamics.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.ballistics.hit.GltfReader;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelRig;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.ConvexHull;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.wflib.physics.api.SweptHull;
import com.wf.wflib.physics.api.ConvexSweep;
import org.joml.Quaternionf;

/**
 * Convex collision hulls: the shapes a pack declares beside its structure model.
 *
 * <p>Two things can go wrong that a load-time check cannot see. A hull can be geometrically valid and
 * still be built into the wrong place, because it is authored in the vehicle's frame and has to land in
 * a bone's; and a hull can quietly fail to reach the vehicle at all, leaving the boxes in place and
 * every other test passing. Both are checked here against a vehicle that ships one.
 *
 * <p>The arithmetic is checked too, because a hull's volume and inertia are numbers nothing else in the
 * game would notice being wrong.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HullShapeGameTest {

    private static final String TEMPLATE = "empty";

    /** The shipped vehicle that declares hulls. */
    private static final ResourceLocation HULLED = CombinedAssault.modLocation("m1a2");

    /** Vehicle-local blocks. Loose enough for float, far tighter than any shape mistake. */
    private static final float TOLERANCE = 1.0e-3f;

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theBodyIsAWedgeAndNotABox(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        VehicleCubeOBB body = hulledCube(vehicle);
        if (body == null) {
            helper.fail("no cube on " + HULLED + " carries a hull, so the pack's hull file never arrived");
            return;
        }
        ConvexHull hull = body.hull();
        if (hull.faceCount() < 5) {
            helper.fail("the body hull has " + hull.faceCount() + " faces, which is a box or less");
            return;
        }
        // A sloped front is the whole point: the solid has to be smaller than the box around it.
        double box = body.width * body.height * body.depth;
        if (hull.volume() >= box - TOLERANCE) {
            helper.fail("the body hull fills its own bound (" + hull.volume() + " of " + box
                    + "), so it is still a box");
            return;
        }
        if (hull.axes().length / 3 < 4) {
            helper.fail("the body hull offers " + hull.axes().length / 3
                    + " separating axes, no more than a box does");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aHullIsCentredOnTheBoxItReports(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        // Everything downstream still reads the cube's box, so a hull whose bound is not that box
        // would be drawn and solved in two different places.
        for (VehicleCubeOBB cube : vehicle.getVehicleCubeOBBs()) {
            ConvexHull hull = cube.hull();
            if (hull == null) {
                continue;
            }
            Vector3f extents = cube.obb().extents();
            if (Math.abs(hull.minX() + extents.x) > TOLERANCE
                    || Math.abs(hull.maxX() - extents.x) > TOLERANCE
                    || Math.abs(hull.minY() + extents.y) > TOLERANCE
                    || Math.abs(hull.maxY() - extents.y) > TOLERANCE
                    || Math.abs(hull.minZ() + extents.z) > TOLERANCE
                    || Math.abs(hull.maxZ() - extents.z) > TOLERANCE) {
                helper.fail("a hull spans x " + hull.minX() + ".." + hull.maxX()
                        + " but its box is " + -extents.x + ".." + extents.x);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aHullLandsInItsOwnBonesFrame(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        Optional<ModelData> model = CommonAssetsManager.modelData(HULLED);
        if (model.isEmpty()) {
            helper.fail("no model data for " + HULLED);
            return;
        }
        // A piece is authored in the vehicle's frame and has to survive the move into its frame's, which for the
        // turret is a real translation. Compare against the untouched collider rather than another conversion.
        for (ModelRig.Collider piece : model.get().rig().colliders()) {
            VehicleCubeOBB cube = cubeFor(vehicle, model.get().rig(), piece);
            if (cube == null) {
                helper.fail("no cube was built for collider " + piece.node());
                return;
            }
            ConvexHull authored = piece.hull();
            double width = authored.maxX() - authored.minX();
            double height = authored.maxY() - authored.minY();
            double depth = authored.maxZ() - authored.minZ();
            if (Math.abs(width - cube.width) > TOLERANCE
                    || Math.abs(height - cube.height) > TOLERANCE
                    || Math.abs(depth - cube.depth) > TOLERANCE) {
                helper.fail("collider " + piece.node() + " was authored " + width + "x" + height
                        + "x" + depth + " and built " + cube.width + "x" + cube.height + "x" + cube.depth);
                return;
            }
            if (Math.abs(authored.volume() - cube.hull().volume()) > TOLERANCE) {
                helper.fail("collider " + piece.node() + " changed volume moving into its frame: "
                        + authored.volume() + " to " + cube.hull().volume());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void hullArithmeticIsExact(GameTestHelper helper) {
        // A unit cube, whose every number is known by hand, then a wedge, whose volume is half a box.
        ConvexHull cube = ConvexHull.box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);
        if (Math.abs(cube.volume() - 1) > 1.0e-5
                || Math.abs(cube.inertia().m00() - 1.0 / 6) > 1.0e-5
                || Math.abs(cube.inertia().m11() - 1.0 / 6) > 1.0e-5
                || Math.abs(cube.inertia().m22() - 1.0 / 6) > 1.0e-5) {
            helper.fail("a unit cube came out with volume " + cube.volume()
                    + " and inertia " + cube.inertia().m00());
            return;
        }
        if (cube.axes().length / 3 != 3 || cube.edgeDirections().length / 3 != 3) {
            helper.fail("a box should offer three face normals and three edge directions, got "
                    + cube.axes().length / 3 + " and " + cube.edgeDirections().length / 3);
            return;
        }
        // Off the origin, where a float integration would lose the inertia to cancellation.
        ConvexHull slab = ConvexHull.box(8, 20, -3, 12, 22, 3);
        if (Math.abs(slab.inertia().m00() - (2 * 2 + 6 * 6) / 12.0) > 1.0e-4
                || Math.abs(slab.centroid().y - 21) > 1.0e-4) {
            helper.fail("an off-centre box came out with inertia " + slab.inertia().m00()
                    + " about a centroid at y " + slab.centroid().y);
            return;
        }
        ConvexHull wedge = ConvexHull.of(
                new float[]{-1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1, -1, 1, -1, 1, 1, -1},
                new int[][]{{0, 3, 2, 1}, {0, 1, 5, 4}, {3, 0, 4}, {1, 2, 5}, {2, 3, 4, 5}});
        if (Math.abs(wedge.volume() - 4) > 1.0e-4) {
            helper.fail("half a 2x2x2 box came out at " + wedge.volume() + " rather than 4");
            return;
        }
        if (wedge.axes().length / 3 != 4) {
            helper.fail("a wedge should offer four face normals, got " + wedge.axes().length / 3);
            return;
        }
        // A dented box is the mistake an author makes, and it has to be refused rather than solved.
        try {
            ConvexHull.of(new float[]{
                    -1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1,
                    -1, 1, -1, 1, 1, -1, 1, 1, 1, -1, 1, 1, 0, 0, 0,
            }, new int[][]{{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4},
                    {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}, {0, 1, 8}});
            helper.fail("a dented box was accepted as convex");
            return;
        } catch (IllegalArgumentException expected) {
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void displayNodesRideTheirPartBones(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null || vehicle.hits() == null) {
            helper.fail("m1a2 has no hit model");
            return;
        }
        HitModel model = vehicle.hits().model();
        for (String[] expect : new String[][]{{"turret", "turret", "turret"}, {"cannon", "cannon", "turret"},
                {"body", "m1a2", null}}) {
            int at = model.indexOf(expect[0]);
            if (at < 0) {
                helper.fail("no display node " + expect[0]);
                return;
            }
            HitModel.Node node = model.nodes().get(at);
            String group = vehicle.hits().groups()[node.group()].name;
            if (!group.equals(expect[1]) || !java.util.Objects.equals(node.part(), expect[2])) {
                helper.fail(expect[0] + " bound to " + group + " / part " + node.part());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void everyPieceIsSolidUnlessItSaysSo(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HULLED);
        if (vehicle == null) {
            return;
        }
        // The defaults have to be what a plain box always meant, or adding a hull file to a pack
        // changes behaviour nobody asked it to change.
        for (VehicleCubeOBB cube : allCubes(vehicle)) {
            if (!cube.isSolid()) {
                helper.fail("a piece came out not solid without saying so");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void colliderKeysAreStrict(GameTestHelper helper) {
        for (String key : new String[]{"component", "armor", "health"}) {
            JsonObject meta = new JsonObject();
            meta.addProperty(key, 1);
            List<GltfReader.Node> nodes = List.of(
                    new GltfReader.Node("hull", -1, new Matrix4f(), new Matrix4f(), new float[0], new int[0], new int[0], "body", "",
                            new JsonObject()),
                    new GltfReader.Node("piece", 0, new Matrix4f(), new Matrix4f(), new float[0], new int[0], new int[0], "collider",
                            "", meta));
            try {
                ModelRig.of("test", nodes);
                helper.fail("a collider with " + key + " was accepted");
                return;
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().contains("unknown key " + key)) {
                    helper.fail("the refusal did not name " + key + ": " + expected.getMessage());
                    return;
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aWedgeSweepsAsAWedgeAndNotAsItsBox(GameTestHelper helper) {
        // The whole reason the sweep was taught about hulls: a shape that recedes from its own bound
        // has to be allowed the room its bound does not have, or a sloped vehicle stops at nothing.
        ConvexHull wedge = ConvexHull.of(
                new float[]{-1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1, -1, 1, -1, 1, 1, -1},
                new int[][]{{0, 3, 2, 1}, {0, 1, 5, 4}, {3, 0, 4}, {1, 2, 5}, {2, 3, 4, 5}});
        ConvexSweep sweep = new ConvexSweep();
        sweep.pose(wedge, new Quaternionf());
        if (!sweep.ready()) {
            helper.fail("the sweep would not take a wedge");
            return;
        }

        // A block four across, at the height where the wedge has already sloped away. The bound
        // reaches z = 1 up there; the wedge reaches nowhere near it.
        double boxMinZ = 2.0;
        double entryHigh = sweep.entry(0, 0, 0, 0, 0, 6,
                -1, 0.5, boxMinZ, 1, 1.5, boxMinZ + 1);
        // The same block down at the wedge's full section, where shape and bound agree.
        double entryLow = sweep.entry(0, 0, 0, 0, 0, 6,
                -1, -1, boxMinZ, 1, -0.5, boxMinZ + 1);
        if (!(entryHigh > entryLow)) {
            helper.fail("the wedge met a block above its slope at " + entryHigh
                    + " and one at its full section at " + entryLow + "; it should reach the low one first");
            return;
        }
        // The low one is met when the front face has travelled the gap: (2 - 1) of 6.
        if (Math.abs(entryLow - 1.0 / 6) > 0.02) {
            helper.fail("the wedge met a block one away over a step of six at " + entryLow);
            return;
        }
        // Nothing in the way at all is the whole step.
        double clear = sweep.entry(0, 0, 0, 0, 0, 1, -1, 0, 20, 1, 1, 21);
        if (clear < 1.0) {
            helper.fail("a clear step came back as " + clear);
            return;
        }
        // Standing inside something reads as zero, which is what the already-inside path keys on.
        double inside = sweep.entry(0, 0, 0, 0, 0, 0, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        if (inside > 0) {
            helper.fail("a box the wedge is standing in came back as " + inside);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theCastShapeIsCutToTheClimbSkirt(GameTestHelper helper) {
        ConvexHull box = ConvexHull.box(-1, -1, -1, 1, 1, 1);
        ConvexHull cast = SweptHull.castShape(box, 1.0f, -0.5);
        if (cast == null) {
            helper.fail("a box with room above its skirt produced no cast shape");
            return;
        }
        // Everything below the skirt is gone, and the result is drawn back from its own surface so a
        // hull already resting on something is not reported as blocked by it.
        if (cast.volume() >= box.volume()) {
            helper.fail("the cast shape is not smaller than the hull: " + cast.volume());
            return;
        }
        // A skirt higher than the hull is clamped rather than emptying it, so there is always
        // something to cast; what comes back is a sliver at the top rather than nothing.
        // The shape comes back in the cast box's own frame, centred on the band, so what says the
        // trim happened is its height rather than where it sits.
        ConvexHull sliver = SweptHull.castShape(box, 1.0f, 5.0);
        if (sliver == null || sliver.maxY() - sliver.minY() > 0.3f) {
            helper.fail("a skirt above the whole hull gave "
                    + (sliver == null ? "nothing"
                    : "a shape " + (sliver.maxY() - sliver.minY()) + " tall"));
            return;
        }
        // A skirt below the underside asks for no trim at all, and gets the hull back whole.
        ConvexHull whole = SweptHull.castShape(box, 1.0f, -5.0);
        if (whole == null || Math.abs(whole.volume() - box.volume()) > 1.0e-4) {
            helper.fail("a skirt below the hull changed it");
            return;
        }
        helper.succeed();
    }

    private static List<VehicleCubeOBB> allCubes(AbstractVehicle vehicle) {
        List<VehicleCubeOBB> cubes = new ArrayList<>(vehicle.getVehicleCubeOBBs());
        for (var part : vehicle.getPartUnits()) {
            cubes.addAll(part.getPartCubeOBBs());
        }
        return cubes;
    }

    @Nullable
    private static VehicleCubeOBB hulledCube(AbstractVehicle vehicle) {
        for (VehicleCubeOBB cube : vehicle.getVehicleCubeOBBs()) {
            if (cube.hasHull()) {
                return cube;
            }
        }
        return null;
    }

    /**
     * The built cube carrying a given authored piece.
     *
     * <p>By label, not by bone: a bone carries as many pieces as the author drew on it, and the tank
     * puts a hull, two tracks and an engine deck on one.
     */
    @Nullable
    private static VehicleCubeOBB cubeFor(AbstractVehicle vehicle, ModelRig rig, ModelRig.Collider piece) {
        String frame = rig.frames().get(piece.frame()).name();
        for (VehicleCubeOBB cube : allCubes(vehicle)) {
            if (cube.hasHull() && frame.equals(boneOf(cube)) && piece.name().equals(cube.tag().name())) {
                return cube;
            }
        }
        return null;
    }

    private static String boneOf(VehicleCubeOBB cube) {
        var named = cube.group == null ? null : cube.group.namedAncestor();
        return named == null ? "" : named.name;
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        return vehicle;
    }

}
