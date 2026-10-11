package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.PhysicsRig;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.wflib.util.OBB;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SuspensionGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRUCK = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation TRACTOR = CombinedAssault.modLocation("m983");
    private static final ResourceLocation STRYKER = CombinedAssault.modLocation("m1129");
    private static final ResourceLocation CENTAURO = CombinedAssault.modLocation("centauro");
    /** Drawn tyre bottom vs floor at rest, blocks. */
    private static final double TYRE_GAP = 0.08;
    /** Floor lane offset from the arena: arenas sit ~8 blocks apart in one world. */
    private static final int LANE_Z = 400;
    private static final int FLOOR_HALF = 6;

    /** Calibration target: hull 0.01 above ground at curb weight. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aSprungTruckSettlesLevelOnItsWheels(GameTestHelper helper) {
        settle(helper, TRUCK, 0, 1.5);
    }

    /** Tracks are suspension units: one probe per track, two contacts. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aTankSettlesLevelOnItsTracks(GameTestHelper helper) {
        settle(helper, TANK, 2, 1.5);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aTractorSettlesLevelOnEightWheels(GameTestHelper helper) {
        settle(helper, TRACTOR, 6, 1.5);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void aStrykerSettlesOnItsTyres(GameTestHelper helper) {
        settle(helper, STRYKER, 8, 1.5);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void aCentauroSettlesOnItsTyres(GameTestHelper helper) {
        settle(helper, CENTAURO, 7, 1.5);
    }

    /** Heave is not swept by travel; a fall must still stop on the floor. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aDroppedTruckLandsOnItsWheels(GameTestHelper helper) {
        settle(helper, TRUCK, 1, 8);
    }

    /** Travel sweep is horizontal-only for a sprung hull; it must still make way and keep its wheels down. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aSprungTruckDrivesOnItsWheels(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + 3 * 32);
        int length = 48;
        forceStrip(level, start, length, true);
        for (int x = -3; x <= 3; x++) {
            for (int z = -4; z <= length; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        double floor = start.getY() + 1;
        AbstractVehicle vehicle = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 0.5, start.getZ() + 0.5));
        if (vehicle == null) {
            return;
        }
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] startZ = new double[1];
        float[] worst = new float[2];
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                    startZ[0] = vehicle.getZ();
                })
                .thenExecuteFor(120, () -> {
                    worst[0] = Math.max(worst[0], Math.abs(Mth.wrapDegrees(vehicle.getXRot())));
                    worst[1] = Math.max(worst[1], Math.abs(Mth.wrapDegrees(vehicle.getZRot())));
                })
                .thenExecute(() -> {
                    double made = vehicle.getZ() - startZ[0];
                    OBB hull = vehicle.getMainCubeOBB().obb();
                    double bottom = hull.centerY() - hull.extents().y - floor;
                    long grounded = vehicle.getSuspensionUnits().stream().filter(SuspensionUnit::isGrounded).count();
                    String failure = null;
                    if (made < 5) {
                        failure = String.format(Locale.ROOT, "120 ticks of throttle made %.2f blocks", made);
                    } else if (bottom < -0.02) {
                        failure = String.format(Locale.ROOT, "hull sank %.3f into the floor while driving", -bottom);
                    } else if (grounded < vehicle.getSuspensionUnits().size() - 1) {
                        failure = grounded + " wheels down while driving";
                    } else if (worst[0] > 8 || worst[1] > 3) {
                        failure = String.format(Locale.ROOT, "attitude left the drive clamps: pitch %.2f roll %.2f",
                                worst[0], worst[1]);
                    }
                    driver.discard();
                    vehicle.discard();
                    for (int x = -3; x <= 3; x++) {
                        for (int z = -4; z <= length; z++) {
                            level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                    forceStrip(level, start, length, false);
                    if (failure != null) {
                        helper.fail(failure);
                    }
                })
                .thenSucceed();
    }


    private static void forceStrip(ServerLevel level, BlockPos start, int length, boolean forced) {
        for (int cx = (start.getX() - 3) >> 4; cx <= (start.getX() + 3) >> 4; cx++) {
            for (int cz = (start.getZ() - 4) >> 4; cz <= (start.getZ() + length) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }

    private static void settle(GameTestHelper helper, ResourceLocation id, int lane, double height) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * 32);
        int minCx = (centre.getX() - FLOOR_HALF) >> 4, maxCx = (centre.getX() + FLOOR_HALF) >> 4;
        int minCz = (centre.getZ() - FLOOR_HALF) >> 4, maxCz = (centre.getZ() + FLOOR_HALF) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ForcedChunks.set(level, cx, cz, true);
            }
        }
        for (int x = -FLOOR_HALF; x <= FLOOR_HALF; x++) {
            for (int z = -FLOOR_HALF; z <= FLOOR_HALF; z++) {
                level.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        double floor = centre.getY() + 1;
        AbstractVehicle[] vehicle = new AbstractVehicle[1];
        // Spawned into a chunk not yet entity-ticking => never ticks (lane-dependent: fresh chunks only).
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(centre), "lane not entity-ticking yet"))
                .thenExecute(() -> vehicle[0] = spawn(helper, id, new Vec3(centre.getX() + 0.5, floor + height,
                        centre.getZ() + 0.5)))
                .thenIdle(160)
                .thenExecute(() -> {
                    String failure = vehicle[0] == null ? null : check(vehicle[0], floor);
                    if (vehicle[0] != null) {
                        vehicle[0].discard();
                    }
                    for (int x = -FLOOR_HALF; x <= FLOOR_HALF; x++) {
                        for (int z = -FLOOR_HALF; z <= FLOOR_HALF; z++) {
                            level.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                    for (int cx = minCx; cx <= maxCx; cx++) {
                        for (int cz = minCz; cz <= maxCz; cz++) {
                            ForcedChunks.set(level, cx, cz, false);
                        }
                    }
                    if (failure != null) {
                        helper.fail(failure);
                    }
                })
                .thenSucceed();
    }

    @Nullable
    private static String check(AbstractVehicle vehicle, double floor) {
        if (!vehicle.hasSuspension()) {
            return vehicle.getVehicleId() + " has no suspension units";
        }
        int grounded = 0;
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            if (unit.isGrounded()) {
                grounded++;
            }
            if (unit.getCompression() < unit.getMinCompression() || unit.getCompression() > unit.getMaxCompression()) {
                return String.format(Locale.ROOT, "%s compression %.3f outside [%.3f, %.3f]", unit.getId(),
                        unit.getCompression(), unit.getMinCompression(), unit.getMaxCompression());
            }
        }
        if (grounded != vehicle.getSuspensionUnits().size()) {
            return String.format(Locale.ROOT, "%d of %d wheels on the floor; y - floor %.3f pitch %.1f roll %.1f",
                    grounded, vehicle.getSuspensionUnits().size(), vehicle.getY() - floor,
                    Mth.wrapDegrees(vehicle.getXRot()), Mth.wrapDegrees(vehicle.getZRot()));
        }
        if (!vehicle.onGround()) {
            return "resting on its wheels but not on ground";
        }
        float pitch = Mth.wrapDegrees(vehicle.getXRot());
        float roll = Mth.wrapDegrees(vehicle.getZRot());
        if (Math.abs(pitch) > 1 || Math.abs(roll) > 1) {
            return String.format(Locale.ROOT, "not level at rest: pitch %.2f roll %.2f", pitch, roll);
        }
        Vec3 movement = vehicle.getDeltaMovement();
        if (movement.lengthSqr() > 0.005 * 0.005) {
            return "still moving at rest: " + movement;
        }
        OBB hull = vehicle.getMainCubeOBB().obb();
        double bottom = hull.centerY() - hull.extents().y;
        if (bottom < floor - 0.02 || bottom > floor + 1.0) {
            return String.format(Locale.ROOT, "hull bottom %.3f above floor, want 0..1", bottom - floor);
        }
        VehicleHits hits = vehicle.hits();
        if (hits == null) {
            return null;
        }
        StringBuilder gaps = new StringBuilder();
        boolean off = false;
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            if (unit.contactWeight() < 1 || IntStream.range(0, hits.model().nodes().size()).noneMatch(n -> hits.part(n) == unit)) {
                continue; // track: links drawn outside the hit mesh
            }
            // Up through the unit's own mesh at each ground contact.
            for (int row = 0; row < unit.contactCount(); row++) {
                double x = unit.contactX(row), z = unit.contactZ(row);
                VehicleHits.Hit tyre = hits.trace(new Vec3(x, floor - 2, z), new Vec3(x, floor + 2, z),
                        n -> hits.part(n) == unit);
                double gap = tyre == null ? Double.NaN : tyre.point().y - floor;
                off |= !(Math.abs(gap) <= TYRE_GAP);
                gaps.append(String.format(Locale.ROOT, " %s#%d %.3f", unit.getId(), row, gap));
            }
        }
        return off ? "drawn tyre bottom - floor:" + gaps : null;
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id, Vec3 at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        return vehicle;
    }

}
