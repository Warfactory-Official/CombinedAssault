package com.wf.combinedassault.aircraft.gametest;

import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.part.FlapUnit;

/** MQ-9 flaps: stages 0/15/30, lift_gain 0.35, stall_shift 3, max_speed 2.0. */
@PrefixGameTestTemplate(false)
public class FlapsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MQ9 = CombinedAssault.modLocation("mq9");
    /** Clear of the arena grid and AircraftPartsGameTest's lanes (-1300 + 64 i). */
    private static final int LANE_X = -1700;
    /** Arbitrary dynamic-pressure term; lift is linear in it. */
    private static final double F = 1000;

    /** Lane in open air: the span overlaps neighbouring arenas, whose setup discards entities inside them. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 120)
    public static void aNotchDeploysAtTheRotationSpeed(GameTestHelper helper) {
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 90, 0);
        force(helper.getLevel(), lane, true);
        FixedWingVehicle mq9 = spawn(helper, lane);
        if (mq9 == null) {
            force(helper.getLevel(), lane, false);
            return;
        }
        if (mq9.flapUnits.size() != 1) {
            helper.fail("mq9 flap parts " + mq9.flapUnits.size());
            return;
        }
        FlapUnit flaps = mq9.flapUnits.get(0);
        // Engine off: power decays from 100, still above the 20 a surface needs after 20 t; no fuel => 0 at once.
        mq9.setEnergy(mq9.energyInfo.capacityUnits());
        mq9.setPower(100);
        flaps.step(1);
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    float level = flaps.level();
                    mq9.discard();
                    force(helper.getLevel(), lane, false);
                    if (flaps.getStage() != 1 || Math.abs(level - 0.5f) > 1e-3) {
                        helper.fail("after one notch and 20 t: stage " + flaps.getStage() + ", level " + level
                                + " (15 of 30 deg expected)");
                    }
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void fullFlapLiftsMoreAndStallsSooner(GameTestHelper helper) {
        FixedWingVehicle mq9 = spawn(helper);
        if (mq9 == null) {
            return;
        }
        FlapUnit flaps = mq9.flapUnits.get(0);
        double cruise = 5;
        double nearStall = mq9.angleOfAttackMax - 1;
        double cleanCruise = mq9.lift(F, cruise, mq9.liftToDragK, 1);
        double cleanNearStall = mq9.lift(F, nearStall, mq9.liftToDragK, 1);
        flaps.setXRot(30);
        double flapCruise = mq9.lift(F, cruise, mq9.liftToDragK, 1);
        double flapNearStall = mq9.lift(F, nearStall, mq9.liftToDragK, 1);
        mq9.discard();
        if (!(flapCruise > cleanCruise * 1.2) || !(flapNearStall < cleanNearStall * 0.5)) {
            helper.fail(String.format("lift at %.0f deg clean %.0f flaps %.0f; at %.0f deg clean %.0f flaps %.0f",
                    cruise, cleanCruise, flapCruise, nearStall, cleanNearStall, flapNearStall));
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void overspeedRetractsTheFlaps(GameTestHelper helper) {
        FixedWingVehicle mq9 = spawn(helper);
        if (mq9 == null) {
            return;
        }
        FlapUnit flaps = mq9.flapUnits.get(0);
        flaps.step(1);
        flaps.step(1);
        mq9.setDeltaMovement(new Vec3(0, 0, 2.5));
        flaps.tick();
        int stage = flaps.getStage();
        mq9.discard();
        if (stage != 0) {
            helper.fail("flaps at 2.5 b/t over a 2.0 limit still at notch " + stage);
            return;
        }
        helper.succeed();
    }

    @Nullable
    private static FixedWingVehicle spawn(GameTestHelper helper) {
        return spawn(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
    }

    @Nullable
    private static FixedWingVehicle spawn(GameTestHelper helper, BlockPos at) {
        java.util.Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MQ9);
        if (data.isEmpty()) {
            helper.fail(MQ9 + " is not in the roster");
            return null;
        }
        if (!(data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0)
                instanceof FixedWingVehicle mq9)) {
            helper.fail(MQ9 + " is not a fixed-wing vehicle");
            return null;
        }
        mq9.setDisplayId(MQ9);
        if (!helper.getLevel().addFreshEntity(mq9)) {
            helper.fail(MQ9 + " could not be added to the world");
            return null;
        }
        return mq9;
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }
}
