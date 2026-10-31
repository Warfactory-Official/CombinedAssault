package com.wf.combinedassault.dynamics.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.ballistics.hit.VehicleHits;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Systems that are hull geometry rather than parts.
 *
 * <p>Damageable systems used to need a {@code PartUnit}, which is the only thing with a health pool,
 * and no stock vehicle declares a track part or has an engine part at all. So the two systems a crew
 * cares about most had nothing to hit and the mobility model returned full for everything. These
 * check the other end of that: a tagged collision piece, a pool behind it, and a shot finding it.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HullSystemGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void hitNodesBecomePools(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        HullComponents systems = vehicle.hullComponents();
        for (String wanted : new String[]{"left track", "right track", "engine"}) {
            int at = systems.indexOf(wanted);
            if (at < 0 || systems.isImplicit(at)) {
                helper.fail("m1a2 hit model should give a declared " + wanted + " pool; pools "
                        + java.util.stream.IntStream.range(0, systems.size()).mapToObj(systems::id).toList());
                return;
            }
        }
        int left = systems.indexOf("left track");
        if (systems.kind(left) != ComponentKind.TRACK || systems.state(left) != ComponentState.PRISTINE) {
            helper.fail("left track came out " + systems.kind(left) + ", " + systems.state(left));
            return;
        }
        helper.succeed();
    }

    /** Side shot into the track module; plunging shot into the engine compartment: DamageSystem's onward trace. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void anOnwardShotFindsTheModuleInsideTheSkin(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        VehicleHits hits = vehicle.hits();
        int track = hits == null ? -1 : hits.model().indexOf("hit_track_left");
        int engine = hits == null ? -1 : hits.model().indexOf("hit_engine");
        if (track < 0 || engine < 0) {
            helper.fail("m1a2 hit model lacks hit_track_left / hit_engine");
            return;
        }
        Vec3 side = hits.centre(track);
        VehicleHits.Hit hit = hits.module(side.add(8, 0, 0), side.add(-8, 0, 0));
        if (hit == null || hit.node() != track) {
            helper.fail("a side shot at track height found " + name(hits, hit));
            return;
        }
        if (hits.surface(side.add(8, 0, 0), side.add(-8, 0, 0)) == null) {
            helper.fail("the same shot met no drawn surface");
            return;
        }
        Vec3 deck = hits.centre(engine);
        hit = hits.module(deck.add(0, 6, 0), deck.add(0, -6, 0));
        if (hit == null || hit.node() != engine) {
            helper.fail("a plunging shot onto the rear deck found " + name(hits, hit));
            return;
        }
        helper.succeed();
    }

    private static String name(VehicleHits hits, @Nullable VehicleHits.Hit hit) {
        return hit == null ? "nothing" : hits.model().nodes().get(hit.node()).mesh().name();
    }

    /**
     * Armour and part hit resolution both go through {@link OBB#first}, and a shot is a
     * segment between two world coordinates. Narrowing those to float before subtracting spends the
     * mantissa on a magnitude that cancels, so a long way out the answer drifts by about a block:
     * enough to report the wrong plate, or the wrong part. Same boxes, same ray, two places.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aShotLandsInTheSamePlaceFarFromSpawn(GameTestHelper helper) {
        double near = shotDepth(0.5, 64.5, 0.5);
        double far = shotDepth(11_000_000.5, 64.5, -3_100_000.5);
        if (Math.abs(near - 3.75) > 1.0e-4) {
            helper.fail("a shot near spawn entered the box at " + near + ", and its face is at 3.75");
            return;
        }
        if (Math.abs(far - near) > 0.01) {
            helper.fail("the same shot entered at " + near + " near spawn and " + far
                    + " eleven million blocks out");
            return;
        }
        helper.succeed();
    }

    /** How far along a shot fired at +Z the first box is met, with the boxes built around a point. */
    private static double shotDepth(double x, double y, double z) {
        List<OBB> boxes = new ArrayList<>();
        boxes.add(box(x, y, z + 4, 0.25F));
        boxes.add(box(x, y, z + 12, 2.0F));
        OBB.SegmentHit hit = OBB.first(boxes, new Vec3(x, y, z), new Vec3(x, y, z + 20));
        return hit == null ? Double.NaN : hit.point().z - z;
    }

    private static OBB box(double x, double y, double z, float half) {
        OBB obb = new OBB(new Vector3f(), new Vector3f(half, half, half), new Quaternionf());
        obb.setCenter(x, y, z);
        return obb;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void breakingTheRunningGearCostsMobility(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        if (Math.abs(VehicleMobility.of(vehicle).speedScale() - 1) > 1.0e-4) {
            helper.fail("an undamaged tank does not have full mobility");
            return;
        }
        HullComponents systems = vehicle.hullComponents();
        int left = systems.indexOf("left track");
        // Worn first: a track that has taken a knock should still drive, just not as well.
        systems.hurt("left track", systems.maxHealth(left) * 0.4f);
        float worn = VehicleMobility.of(vehicle).speedScale();
        if (!(worn < 1) || !(worn > 0)) {
            helper.fail("a worn track gave a speed scale of " + worn);
            return;
        }
        // Then thrown: broken is flat zero, and it takes the steering with it.
        systems.hurt("left track", systems.maxHealth(left));
        VehicleMobility broken = VehicleMobility.of(vehicle);
        if (systems.state(left) != ComponentState.BROKEN) {
            helper.fail("a track at zero health reads as " + systems.state(left));
            return;
        }
        if (broken.speedScale() != 0 || broken.turnScale() != 0) {
            helper.fail("a thrown track left speed " + broken.speedScale()
                    + " and turn " + broken.turnScale());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void damageSurvivesASave(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        HullComponents systems = vehicle.hullComponents();
        int track = systems.indexOf("left track");
        systems.hurt("left track", systems.maxHealth(track) * 0.5f);
        float damaged = systems.health(track);

        CompoundTag saved = new CompoundTag();
        vehicle.addAdditionalSaveData(saved);

        AbstractVehicle reloaded = spawn(helper, TANK);
        if (reloaded == null) {
            return;
        }
        reloaded.readAdditionalSaveData(saved);
        HullComponents restored = reloaded.hullComponents();
        int again = restored.indexOf("left track");
        if (again < 0) {
            helper.fail("the reloaded tank has no left track");
            return;
        }
        if (Math.abs(restored.health(again) - damaged) > 0.01f) {
            helper.fail("a thrown track came back at " + restored.health(again)
                    + " rather than " + damaged);
            return;
        }
        // And a system that was never touched comes back whole rather than at zero.
        int deck = restored.indexOf("engine");
        if (restored.state(deck) != ComponentState.PRISTINE) {
            helper.fail("an undamaged system came back as " + restored.state(deck));
            return;
        }
        helper.succeed();
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
        vehicle.updateOBBs();
        return vehicle;
    }

}
