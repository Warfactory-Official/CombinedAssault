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

/**
 * Jets: nose level at 270 km/h holds height; full throttle tops out at 320 km/h.
 */
@PrefixGameTestTemplate(false)
public class LevelTrimGameTest {

    private static final String TEMPLATE = "empty";
    /** Clear of FlapsGameTest (-1700), WingLossGameTest (-2100..-1812), AircraftPartsGameTest (-1300 + 64 i). */
    private static final int LANE_X = -2600;
    private static final int FLY_TICKS = 40;

    private static final String[] JETS = {"a10", "av8b", "b2a", "f22a", "f35a", "su33", "su47", "tornado"};
    /** b/t: 270 km/h cruise (level trim AoA 0.5 deg), 320 km/h top (thrust == drag). */
    private static final double CRUISE = 3.75;
    private static final double TOP = 320 / 72.0;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void a10HoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 0);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void av8bHoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 1);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void b2aHoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 2);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void f22aHoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 3);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void f35aHoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 4);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void su33HoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 5);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void su47HoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 6);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void tornadoHoldsHeightAtLevelNose(GameTestHelper helper) {
        holdsHeight(helper, 7);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void jetsTopOutNear320(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 120, 0);
        FixedWingVehicle[] jets = new FixedWingVehicle[JETS.length];
        for (int i = 0; i < JETS.length; i++) {
            BlockPos lane = base.offset(0, 0, 0).offset(-64 * (i + 1), 0, 0);
            force(level, lane, true, 36);
            jets[i] = spawn(helper, lane, JETS[i]);
            if (jets[i] == null) {
                return;
            }
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (FixedWingVehicle jet : jets) {
                        helper.assertTrue(jet.tickCount > 3, "not ticking");
                    }
                })
                .thenExecute(() -> {
                    for (FixedWingVehicle jet : jets) {
                        launch(jet, TOP * 0.9, 100 * Math.max(1, jet.thrustK));
                    }
                })
                .thenIdle(140)
                .thenExecute(() -> {
                    StringBuilder report = new StringBuilder();
                    boolean ok = true;
                    for (int i = 0; i < jets.length; i++) {
                        double kmh = jets[i].getDeltaMovement().length() * 72;
                        report.append(String.format(Locale.ROOT, "%s %.0f km/h y%.0f; ", JETS[i], kmh, jets[i].getY()));
                        ok &= kmh > 300 && kmh < 340;
                        jets[i].discard();
                        force(level, base.offset(-64 * (i + 1), 0, 0), false, 36);
                    }
                    CombinedAssault.LOGGER.info("[leveltrim] top speed: {}", report);
                    if (!ok) {
                        helper.fail(report.toString());
                    }
                })
                .thenSucceed();
    }

    private static void launch(FixedWingVehicle jet, double speed, double throttle) {
        jet.setEnergy(jet.energyInfo.capacityUnits());
        jet.setPower(100);
        jet.toggleEngine(true);
        if (jet.landingGear != null) {
            jet.landingGear.setOn(true);
        }
        jet.setThrottleLevel((float) throttle);
        jet.setDeltaMovement(jet.getLookAngle().scale(speed));
    }

    /** Nose level at 270 km/h, throttle at the thrust == drag point: height held, pitch unmoved. */
    private static void holdsHeight(GameTestHelper helper, int index) {
        String id = JETS[index];
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(LANE_X - 700 - 64 * index, 120, 0);
        force(level, lane, true, 10);
        FixedWingVehicle jet = spawn(helper, lane, id);
        if (jet == null) {
            force(level, lane, false, 10);
            return;
        }
        double[] start = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jet.tickCount > 3, id + " not ticking"))
                .thenExecute(() -> {
                    launch(jet, CRUISE, 100 * Math.max(1, jet.thrustK) * (CRUISE * CRUISE) / (TOP * TOP));
                    start[0] = jet.getY();
                    start[1] = jet.tickCount;
                })
                .thenIdle(FLY_TICKS)
                .thenExecute(() -> {
                    double dy = jet.getY() - start[0];
                    double ticks = jet.tickCount - start[1];
                    float pitch = jet.getXRot();
                    double speed = jet.getDeltaMovement().length();
                    jet.discard();
                    force(level, lane, false, 10);
                    String state = String.format(Locale.ROOT, "%s %.0f t: y %.0f dy %.2f, pitch %.2f, speed %.2f b/t", id, ticks, jet.getY(), dy, pitch, speed);
                    CombinedAssault.LOGGER.info("[leveltrim] {}", state);
                    if (ticks < FLY_TICKS / 2.0 || Math.abs(dy) > 2 || Math.abs(pitch) > 3 || speed < CRUISE * 0.85) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    @Nullable
    private static FixedWingVehicle spawn(GameTestHelper helper, BlockPos at, String id) {
        ResourceLocation vehicle = CombinedAssault.modLocation(id);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle);
        if (data.isEmpty()) {
            helper.fail(vehicle + " is not in the roster");
            return null;
        }
        if (!(data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0)
                instanceof FixedWingVehicle b2)) {
            helper.fail(vehicle + " is not a fixed-wing vehicle");
            return null;
        }
        b2.setDisplayId(vehicle);
        if (!helper.getLevel().addFreshEntity(b2)) {
            helper.fail(vehicle + " could not be added to the world");
            return null;
        }
        return b2;
    }

    /** Lane runs +z (yaw 0), {@code chunks} long. */
    private static void force(ServerLevel level, BlockPos at, boolean forced, int chunks) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -2; dz <= chunks; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }
}
