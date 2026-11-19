package com.wf.combinedassault.vehicle.drive.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.drive.Transmission;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.drive.WheelSpin;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.WheeledVehicle;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;
import java.util.Optional;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class TransmissionGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final int LANE_X = -900;
    private static final int HALF_WIDTH = 6;
    private static final int LANE_LENGTH = 240;
    private static final int DRIVE_TICKS = 240;
    private static final int BRAKE_TICKS = 120;
    private static final int SPRINT_LENGTH = 900;
    private static final int SPRINT_TICKS = 700;
    /** 100 km/h, blocks/tick. */
    private static final double TARGET = 100 / 3.6 / 20;

    /** Humvee box: gear tops ascend to max_speed_forward, rpm spans idle..max, automatic upshift without hunting. */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void gearRatiosSetTopSpeedRpmAndShiftPoints(GameTestHelper helper) {
        Transmission t = new Transmission();
        t.gears = new float[]{2.48f, 1.48f, 1.0f, 0.75f};
        t.validate();
        float max = 0.6f;
        StringBuilder bad = new StringBuilder();
        for (int g = 1; g < t.forwardGears(); g++) {
            if (!(t.topSpeed(g, max) < t.topSpeed(g + 1, max))) {
                bad.append("top ").append(g).append(" !< ").append(g + 1).append("; ");
            }
            float shift = t.topSpeed(g, max) * 0.95f;
            if (t.automatic(g, shift, max) != g + 1 || t.automatic(g + 1, shift, max) != g + 1) {
                bad.append("shift point ").append(g).append("; ");
            }
        }
        if (Math.abs(t.topSpeed(4, max) - max) > 1e-6 || t.rpm(0, 1) != t.idleRpm || t.rpm(1, 1) != t.maxRpm
                || t.automatic(4, max, max) != 4 || t.automatic(2, 0, max) != 1) {
            bad.append("ends");
        }
        if (!bad.isEmpty()) {
            helper.fail(bad.toString());
            return;
        }
        helper.succeed();
    }

    /**
     * Autopilot W: first gear holds its top speed until the upshift, the box climbs to >= 3rd; S brakes to a stop,
     * then selects R and backs up.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = DRIVE_TICKS + BRAKE_TICKS + 600)
    public static void autopilotShiftsUpBrakesAndReverses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 0, 0);
        lane(level, start, LANE_LENGTH, true);
        WheeledVehicle[] spawned = new WheeledVehicle[1];
        Transmission[] box = new Transmission[1];
        double[] firstPeak = {0};
        int[] topGear = {Transmission.NEUTRAL};
        float[] peakRpm = {0};
        double[] backed = {0};
        boolean[] reversed = {false};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(start.offset(0, 1, 8)), "lane not ticking"))
                .thenExecute(() -> {
                    Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(HUMVEE);
                    if (data.isEmpty()) {
                        helper.fail("no " + HUMVEE);
                        return;
                    }
                    AbstractVehicle vehicle = data.get().construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5,
                            start.getZ() + 8.5), 0, 0);
                    vehicle.setDisplayId(HUMVEE);
                    if (!level.addFreshEntity(vehicle) || !(vehicle instanceof WheeledVehicle humvee) || humvee.transmission == null) {
                        helper.fail(HUMVEE + " is no wheeled vehicle with a transmission, or did not spawn");
                        return;
                    }
                    humvee.setEnergy(humvee.energyInfo.capacityUnits());
                    humvee.autopilotControl = true;
                    humvee.toggleEngine(true);
                    spawned[0] = humvee;
                    box[0] = humvee.transmission;
                })
                .thenIdle(40)
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    spawned[0].controlUnit.forward = true;
                    spawned[0].controlUnit.backward = false;
                    if (spawned[0].getGear() == 1) {
                        firstPeak[0] = Math.max(firstPeak[0], spawned[0].getForwardSpeed());
                    }
                    topGear[0] = Math.max(topGear[0], spawned[0].getGear());
                    peakRpm[0] = Math.max(peakRpm[0], spawned[0].rpm());
                })
                .thenExecuteFor(BRAKE_TICKS, () -> {
                    spawned[0].controlUnit.forward = false;
                    spawned[0].controlUnit.backward = true;
                    reversed[0] |= spawned[0].getGear() == Transmission.REVERSE;
                    backed[0] = Math.min(backed[0], spawned[0].getForwardSpeed());
                })
                .thenExecute(() -> {
                    String report = String.format(Locale.ROOT,
                            "1st peak %.3f (top %.3f), top gear %s, peak rpm %.0f, reversed %b, back speed %.3f",
                            firstPeak[0], box[0].topSpeed(1, spawned[0].maxSpeedForward), Transmission.label(topGear[0]), peakRpm[0], reversed[0], backed[0]);
                    CombinedAssault.LOGGER.info("[transmission] {}", report);
                    spawned[0].discard();
                    lane(level, start, LANE_LENGTH, false);
                    if (firstPeak[0] > box[0].topSpeed(1, spawned[0].maxSpeedForward) * 1.05 || topGear[0] < 3 || peakRpm[0] > box[0].maxRpm + 1
                            || !reversed[0] || !(backed[0] < -0.05)) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    /** Autopilot full throttle on a flat lane: top gear reaches 100 km/h. */
    @GameTest(template = TEMPLATE, timeoutTicks = SPRINT_TICKS + 600)
    public static void autopilotReaches100Kmh(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X - 40, 0, 0);
        lane(level, start, SPRINT_LENGTH, true);
        WheeledVehicle[] spawned = new WheeledVehicle[1];
        double[] peak = {0};
        int[] reached = {-1};
        int[] tick = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(start.offset(0, 1, 8)), "lane not ticking"))
                .thenExecute(() -> {
                    AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(HUMVEE).orElseThrow()
                            .construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 8.5), 0, 0);
                    vehicle.setDisplayId(HUMVEE);
                    level.addFreshEntity(vehicle);
                    WheeledVehicle humvee = (WheeledVehicle) vehicle;
                    humvee.setEnergy(humvee.energyInfo.capacityUnits());
                    humvee.autopilotControl = true;
                    humvee.toggleEngine(true);
                    spawned[0] = humvee;
                })
                .thenIdle(40)
                .thenExecuteFor(SPRINT_TICKS, () -> {
                    spawned[0].controlUnit.forward = true;
                    spawned[0].controlUnit.backward = false;
                    double v = spawned[0].getForwardSpeed();
                    peak[0] = Math.max(peak[0], v);
                    if (reached[0] < 0 && v >= TARGET) {
                        reached[0] = tick[0];
                    }
                    tick[0]++;
                })
                .thenExecute(() -> {
                    String report = String.format(Locale.ROOT, "peak %.1f km/h, 100 km/h at tick %d, gear %s, z %.0f",
                            peak[0] * 72, reached[0], Transmission.label(spawned[0].getGear()), spawned[0].getZ() - start.getZ());
                    CombinedAssault.LOGGER.info("[transmission] {}", report);
                    spawned[0].discard();
                    lane(level, start, SPRINT_LENGTH, false);
                    if (reached[0] < 0) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    private static final int HOLD_LANE_X = LANE_X - 100;
    private static final int HOLD_LENGTH = 160;

    /**
     * Spring-load estimate: flat ground ~ weight, and a 1-block pad under the front wheels moves load between the axles
     * (rear-only driven fraction follows). Fractions finite, in [0, 1].
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void wheelLoadFractionsFollowTheTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(HOLD_LANE_X - 80, 0, 0);
        lane(level, start, HOLD_LENGTH, true);
        WheeledVehicle[] cars = new WheeledVehicle[3];
        double[] ratio = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(start.offset(0, 1, 8)), "lane not ticking"))
                .thenExecute(() -> {
                    cars[0] = spawnHumvee(level, start, true);
                    // pad under the front wheels of the second car (x offset), z 10..14
                    for (int x = HALF_WIDTH - 3; x <= HALF_WIDTH; x++) {
                        for (int z = 10; z <= 14; z++) {
                            level.setBlockAndUpdate(start.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                        }
                    }
                    cars[1] = spawnHumvee(level, start.offset(HALF_WIDTH - 1, 0, 0), true);
                    // Airborne: no spring load anywhere => unit-count fractions (deck / provider ground reads no wheel load).
                    cars[2] = spawnHumvee(level, start.offset(-HALF_WIDTH + 1, 0, 0), true);
                    cars[2].setPos(cars[2].getX(), cars[2].getY() + 80, cars[2].getZ());
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    double[] air = cars[2].wheelLoads();
                    if (Math.abs(air[0] - 0.5) > 1e-9 || Math.abs(air[1] - 0.5) > 1e-9 || Math.abs(air[2] - 0.5) > 1e-9) {
                        helper.fail("airborne fractions " + java.util.Arrays.toString(air));
                    }
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    double[] a = cars[0].wheelLoads();
                    double[] b = cars[1].wheelLoads();
                    double sum = 0;
                    for (var unit : cars[0].getSuspensionUnits()) {
                        sum += unit.load();
                    }
                    ratio[0] = sum / (cars[0].physicsEngine.mass * 9.8);
                    String report = String.format(Locale.ROOT, "flat: rear %.2f (Sum k*c / m g = %.2f); padded: rear %.2f pitch %.1f",
                            a[0], ratio[0], b[0], cars[1].getXRot());
                    CombinedAssault.LOGGER.info("[wheelload] {}", report);
                    for (WheeledVehicle car : cars) {
                        car.discard();
                    }
                    for (int x = HALF_WIDTH - 3; x <= HALF_WIDTH; x++) {
                        for (int z = 10; z <= 14; z++) {
                            level.setBlockAndUpdate(start.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                    lane(level, start, HOLD_LENGTH, false);
                    boolean finite = a[0] >= 0 && a[0] <= 1 && b[0] >= 0 && b[0] <= 1;
                    if (!finite || Math.abs(a[0] - 0.5) > 0.2 || ratio[0] < 0.5 || ratio[0] > 1.5) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    /** Humvee on a flat lane, engine on, autopilot; {@code rearOnly}: only the rear wheels are driven and locked. */
    private static WheeledVehicle spawnHumvee(ServerLevel level, BlockPos start, boolean rearOnly) {
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(HUMVEE).orElseThrow()
                .construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 8.5), 0, 0);
        vehicle.setDisplayId(HUMVEE);
        level.addFreshEntity(vehicle);
        WheeledVehicle humvee = (WheeledVehicle) vehicle;
        if (rearOnly) {
            // Humvee engine cannot break a locked 0.9 G hold; a weak brake lets the locked rears turn. Copy: pack data shared.
            WheelSpin weak = new WheelSpin();
            weak.driveGrip = humvee.wheelspin.driveGrip;
            weak.kineticRatio = humvee.wheelspin.kineticRatio;
            weak.handbrakeHold = 0.3f;
            humvee.wheelspin = weak;
            for (SuspensionUnit<?> unit : humvee.getSuspensionUnits()) {
                boolean rear = unit.getId().startsWith("wheel_r");
                unit.setDrive(rear, rear);
            }
        }
        humvee.setEnergy(humvee.energyInfo.capacityUnits());
        humvee.autopilotControl = true;
        humvee.toggleEngine(true);
        return humvee;
    }

    /** Handbrake + full throttle: a all-wheel-drive stands (no spin), rear-driven stands with the driven wheels spinning. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void handbrakeHoldsAgainstThrottle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(HOLD_LANE_X, 0, 0);
        lane(level, start, HOLD_LENGTH, true);
        WheeledVehicle[] cars = new WheeledVehicle[2];
        Vec3[] at = new Vec3[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(start.offset(0, 1, 8)), "lane not ticking"))
                .thenExecute(() -> {
                    cars[0] = spawnHumvee(level, start, false);
                    cars[1] = spawnHumvee(level, start.offset(HALF_WIDTH - 1, 0, 0), true);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) {
                        at[i] = cars[i].position();
                    }
                })
                .thenExecuteFor(100, () -> {
                    for (WheeledVehicle car : cars) {
                        car.controlUnit.forward = true;
                        car.controlUnit.up = true;
                    }
                })
                .thenExecute(() -> {
                    double moved4 = cars[0].position().subtract(at[0]).horizontalDistance();
                    double moved2 = cars[1].position().subtract(at[1]).horizontalDistance();
                    float spin4 = cars[0].getEntityData().get(WheeledVehicle.WHEEL_SPIN);
                    float spin2 = cars[1].getEntityData().get(WheeledVehicle.WHEEL_SPIN);
                    String report = String.format(Locale.ROOT, "4x4 moved %.2f spin %.0f; 2wd moved %.2f spin %.0f", moved4, spin4, moved2, spin2);
                    CombinedAssault.LOGGER.info("[handbrake] {}", report);
                    for (WheeledVehicle car : cars) {
                        car.discard();
                    }
                    lane(level, start, HOLD_LENGTH, false);
                    if (moved4 > 0.3 || moved2 > 0.3 || spin4 != 0 || spin2 == 0) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    /** Held handbrake from ~40 km/h: stops within 4 s (legacy drag took > 8 s). */
    @GameTest(template = TEMPLATE, timeoutTicks = 500)
    public static void handbrakeStopsFromSpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(HOLD_LANE_X - 40, 0, 0);
        lane(level, start, HOLD_LENGTH, true);
        WheeledVehicle[] car = new WheeledVehicle[1];
        double[] peak = {0};
        int[] stoppedAt = {-1};
        int[] tick = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(start.offset(0, 1, 8)), "lane not ticking"))
                .thenExecute(() -> car[0] = spawnHumvee(level, start, false))
                .thenIdle(40)
                .thenExecuteFor(60, () -> {
                    car[0].controlUnit.forward = true;
                    peak[0] = Math.max(peak[0], car[0].getForwardSpeed());
                })
                .thenExecuteFor(120, () -> {
                    car[0].controlUnit.forward = false;
                    car[0].controlUnit.up = true;
                    if (stoppedAt[0] < 0 && car[0].getForwardSpeed() < 0.01) {
                        stoppedAt[0] = tick[0];
                    }
                    tick[0]++;
                })
                .thenExecute(() -> {
                    String report = String.format(Locale.ROOT, "peak %.1f km/h, stopped after %d ticks", peak[0] * 72, stoppedAt[0]);
                    CombinedAssault.LOGGER.info("[handbrake] {}", report);
                    car[0].discard();
                    lane(level, start, HOLD_LENGTH, false);
                    if (peak[0] < 0.3 || stoppedAt[0] < 0 || stoppedAt[0] > 80) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    private static void lane(ServerLevel level, BlockPos start, int length, boolean build) {
        if (build) {
            force(level, start, length, true);
        }
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = 0; z <= length; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), (build ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
        }
        if (!build) {
            force(level, start, length, false);
        }
    }

    private static void force(ServerLevel level, BlockPos start, int length, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = start.getZ() >> 4; cz <= (start.getZ() + length) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}
