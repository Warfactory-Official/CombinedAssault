package com.wf.combinedassault.aircraft.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/** Old lift curve: B-2 at full throttle, nose held level, climbed (lift ~3.5x weight at AoA 0). */
@PrefixGameTestTemplate(false)
public class LevelTrimGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation B2 = CombinedAssault.modLocation("b2a");
    /** Clear of FlapsGameTest (-1700), WingLossGameTest (-2100..-1812), AircraftPartsGameTest (-1300 + 64 i). */
    private static final int LANE_X = -2600;
    /** b/t; thrust == drag at ~2.75. */
    private static final double CRUISE = 2.6;
    private static final int FLY_TICKS = 40;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aLevelNoseAtFullThrottleDoesNotClimb(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 120, 0);
        force(level, lane, true);
        FixedWingVehicle b2 = spawn(helper, lane);
        if (b2 == null) {
            force(level, lane, false);
            return;
        }
        double[] start = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(b2.tickCount > 3, "b2a not ticking"))
                .thenExecute(() -> {
                    b2.setEnergy(b2.energyInfo.capacityUnits());
                    b2.setPower(100);
                    b2.toggleEngine(true);
                    b2.setThrottleLevel(100);
                    b2.setDeltaMovement(b2.getLookAngle().scale(CRUISE));
                    start[0] = b2.getY();
                    start[1] = b2.tickCount;
                })
                .thenIdle(FLY_TICKS)
                .thenExecute(() -> {
                    double climb = b2.getY() - start[0];
                    double ticks = b2.tickCount - start[1];
                    double speed = b2.getDeltaMovement().length();
                    float pitch = b2.getXRot();
                    b2.discard();
                    force(level, lane, false);
                    String state = String.format(Locale.ROOT, "%.0f t: climb %.2f, pitch %.2f, speed %.2f b/t",
                            ticks, climb, pitch, speed);
                    CombinedAssault.LOGGER.info("[leveltrim] {}", state);
                    if (ticks < FLY_TICKS / 2.0 || climb > 1 || Math.abs(pitch) > 3 || speed < CRUISE * 0.8) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    @Nullable
    private static FixedWingVehicle spawn(GameTestHelper helper, BlockPos at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(B2);
        if (data.isEmpty()) {
            helper.fail(B2 + " is not in the roster");
            return null;
        }
        if (!(data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0)
                instanceof FixedWingVehicle b2)) {
            helper.fail(B2 + " is not a fixed-wing vehicle");
            return null;
        }
        b2.setDisplayId(B2);
        if (!helper.getLevel().addFreshEntity(b2)) {
            helper.fail(B2 + " could not be added to the world");
            return null;
        }
        return b2;
    }

    /** Lane runs +z (yaw 0) ~110 blocks. */
    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= 10; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }
}
