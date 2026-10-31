package com.wf.combinedassault.dynamics.gametest;

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
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.wflib.physics.api.BoxBuffer;
import com.wf.wflib.physics.api.CompoundBody;
import com.wf.wflib.physics.api.SweptHull;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.geom.api.OBB;
import com.wf.wflib.physics.api.HullPiece;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

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

        HullPiece gun = pieceNamed(vehicle, "main gun");
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

    @Nullable
    private static HullPiece pieceNamed(AbstractVehicle vehicle, String name) {
        CompoundBody compound = vehicle.collisionBody();
        for (int i = 0; i < compound.pieceCount(); i++) {
            if (name.equals(compound.piece(i).tag().name())) {
                return compound.piece(i);
            }
        }
        return null;
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
