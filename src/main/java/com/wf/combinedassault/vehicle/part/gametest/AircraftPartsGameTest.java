package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.LinkedHingeUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.VariableSweepUnit;

import java.util.Locale;
import java.util.Optional;

/** Harrier VTOL, Tornado sweep, C-5 ramp: the parts that turn a structure bone from vehicle state. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class AircraftPartsGameTest {

    private static final String TEMPLATE = "empty";
    /** Clear of the arena grid, the Pylon lane (-900) and the ship templates (+z). */
    private static final int LANE_X = -1300;
    private static final int LANE_PITCH = 64;

    /** Nozzles down at full power: holds height. Nozzles aft, same power, from rest: falls. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aHarrierHoversOnItsNozzles(GameTestHelper helper) {
        FixedWingVehicle down = plane(helper, "av8b", 0);
        FixedWingVehicle aft = down == null ? null : plane(helper, "av8b", 1);
        if (aft == null) {
            return;
        }
        down.thrustUnit.setXAimRot(-90);
        down.thrustUnit.setXRot(-90);
        double[] start = {down.getY(), aft.getY()};
        helper.startSequence().thenExecuteFor(80, () -> {
            down.setThrottleLevel(100);
            aft.setThrottleLevel(100);
        }).thenExecute(() -> {
            double held = down.getY() - start[0], fell = aft.getY() - start[1];
            String report = String.format(Locale.ROOT, "[vtol] nozzles down dy %.2f, aft dy %.2f, nozzle %.1f",
                    held, fell, down.thrustUnit.getXRot());
            CombinedAssault.LOGGER.info(report);
            release(helper, 0, down);
            release(helper, 1, aft);
            if (held < -1 || fell > -3) {
                helper.fail(report);
            }
        }).thenSucceed();
    }

    /** Fast => both wings at full sweep, mirrored; their bones (hull, hits) turn with them. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aTornadoSweepsItsWingsWithSpeed(GameTestHelper helper) {
        FixedWingVehicle tornado = plane(helper, "tornado", 2);
        if (tornado == null) {
            return;
        }
        VariableSweepUnit left = part(helper, tornado, "wing_l", VariableSweepUnit.class);
        VariableSweepUnit right = part(helper, tornado, "wing_r", VariableSweepUnit.class);
        if (left == null || right == null) {
            release(helper, 2, tornado);
            return;
        }
        float rest = left.getYRot();
        Vec3 pinned = tornado.position();
        // Pinned: at 3 b/t it leaves the forced chunks within ~13 ticks and stops ticking.
        helper.startSequence().thenExecuteFor(100, () -> {
            tornado.setThrottleLevel(100);
            tornado.setPos(pinned);
            tornado.setDeltaMovement(tornado.getLookAngle().scale(3.0));
        }).thenExecute(() -> {
            String report = String.format(Locale.ROOT, "[sweep] rest %.1f, fast l %.1f r %.1f (aim %.1f, rate %.2f), speed %.2f",
                    rest, left.getYRot(), right.getYRot(), left.getYAimRot(), left.getYRotSpeed(),
                    tornado.getDeltaMovement().length());
            CombinedAssault.LOGGER.info(report);
            release(helper, 2, tornado);
            if (Math.abs(rest) > 0.5 || Math.abs(left.getYRot() + 35) > 0.5 || Math.abs(right.getYRot() - 35) > 0.5) {
                helper.fail(report);
            }
        }).thenSucceed();
    }

    /** Doors on => ramp hinge swings to its open angle; the C-5 hold is a deck. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aGalaxyLowersItsRampWithTheDoors(GameTestHelper helper) {
        FixedWingVehicle c5 = plane(helper, "c5", 3);
        if (c5 == null) {
            return;
        }
        DoorUnit doors = part(helper, c5, "cargo_doors", DoorUnit.class);
        LinkedHingeUnit ramp = part(helper, c5, "ramp", LinkedHingeUnit.class);
        if (doors == null || ramp == null) {
            release(helper, 3, c5);
            return;
        }
        boolean deck = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation("c5"))
                .map(BaseVehicleData::hasDeckGeometry).orElse(false);
        float closed = ramp.getXRot();
        doors.setOn(true);
        helper.startSequence().thenIdle(60).thenExecute(() -> {
            String report = String.format(Locale.ROOT, "[ramp] closed %.1f, open %.1f, deck %s", closed, ramp.getXRot(), deck);
            CombinedAssault.LOGGER.info(report);
            release(helper, 3, c5);
            if (!deck || Math.abs(closed) > 0.01 || Math.abs(ramp.getXRot() + 20) > 0.01) {
                helper.fail(report);
            }
        }).thenSucceed();
    }

    /** In the air, well above the lane floor, engine running. */
    @Nullable
    private static FixedWingVehicle plane(GameTestHelper helper, String name, int lane) {
        ResourceLocation id = CombinedAssault.modLocation(name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        BlockPos at = lane(helper, lane);
        force(helper.getLevel(), at, true);
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!(vehicle instanceof FixedWingVehicle plane) || !helper.getLevel().addFreshEntity(vehicle)) {
            force(helper.getLevel(), at, false);
            helper.fail(id + " is not a fixed-wing aircraft in the world");
            return null;
        }
        plane.setEnergy(plane.energyInfo.capacityUnits());
        plane.toggleEngine(true);
        plane.setPower(100);
        return plane;
    }

    @Nullable
    private static <T extends PartUnit<?>> T part(GameTestHelper helper, AbstractVehicle vehicle, String id, Class<T> type) {
        Object unit = vehicle.getPartUnit(id).orElse(null);
        if (!type.isInstance(unit)) {
            helper.fail(vehicle.getDisplayId() + " part " + id + " is " + unit + ", not " + type.getSimpleName());
            return null;
        }
        return type.cast(unit);
    }

    private static BlockPos lane(GameTestHelper helper, int lane) {
        return helper.absolutePos(BlockPos.ZERO).offset(LANE_X + lane * LANE_PITCH, 90, 0);
    }

    private static void release(GameTestHelper helper, int lane, AbstractVehicle vehicle) {
        vehicle.discard();
        force(helper.getLevel(), lane(helper, lane), false);
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }

}
