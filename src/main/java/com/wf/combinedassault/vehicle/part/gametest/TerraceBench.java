package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Natural-terrain terraces: {@link #STEPS} 1-block steps {@link #TREAD} apart, approached at an angle from rest against
 * the first face or from a run-up, or driven along an edge (side). One {@code [terrace]} row per case.
 * {@code -Pbench=terrace}.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class TerraceBench {

    private static final String[] VEHICLES = {"humvee", "m1129", "m983", "t90", "m1a2"};
    private static final int[] ANGLES = {0, 30, 60};
    private static final int STEPS = 3;
    private static final int TREAD = 4;
    private static final int LANE_X = -9000;
    private static final int LANE_PITCH = 80;
    private static final int HALF_WIDTH = 36;
    private static final int BEFORE = 20;
    private static final int AFTER = 40;
    private static final int RUN_UP = 8;
    private static final int SETTLE_TICKS = 30;
    private static final int DRIVE_TICKS = 400;

    @GameTestGenerator
    public static List<TestFunction> terraceBench() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (int angle : ANGLES) {
                for (boolean rest : new boolean[]{true, false}) {
                    String name = String.format(Locale.ROOT, "terrace_%s_%d_%s", vehicle, angle, rest ? "rest" : "run");
                    if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                        continue;
                    }
                    int at = lane++;
                    tests.add(new TestFunction("terrace", name, ClimbBench.NAMESPACE + ":empty",
                            SETTLE_TICKS + DRIVE_TICKS + 100, 0, false, helper -> run(helper, name, vehicle, angle, rest, at)));
                }
            }
            for (boolean weave : new boolean[]{false, true}) {
                String name = String.format(Locale.ROOT, "terrace_%s_side_%s", vehicle, weave ? "weave" : "straight");
                if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                    continue;
                }
                int at = lane++;
                tests.add(new TestFunction("terrace", name, ClimbBench.NAMESPACE + ":empty",
                        SETTLE_TICKS + DRIVE_TICKS + 100, 0, false, helper -> side(helper, name, vehicle, weave, at)));
            }
        }
        return tests;
    }

    /** Along the edges, straddling the second terrace edge (one side a block up); weave = steer flips every 20 ticks. */
    private static void side(GameTestHelper helper, String test, String name, boolean weave, int lane) {
        ServerLevel level = helper.getLevel();
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation(name));
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[terrace] {} absent", test);
            helper.succeed();
            return;
        }
        BlockPos start = new BlockPos(LANE_X + lane * LANE_PITCH, helper.absolutePos(BlockPos.ZERO).getY() - 1, 3000);
        setChunks(level, start, true);
        build(level, start, true);
        double floor = start.getY() + 1;
        AbstractVehicle vehicle = data.get().construct(level,
                new Vec3(start.getX() + 0.5 + HALF_WIDTH - 10, floor + 2.5, start.getZ() + TREAD), 0, 90);
        vehicle.setDisplayId(CombinedAssault.modLocation(name));
        level.addFreshEntity(vehicle);
        vehicle.physicsEngine.canDestroyBlock = false;
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        float[] roll = new float[1];
        int[] flipped = {-1};
        int[] ticks = new int[1];
        double x0 = vehicle.getX();
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS / 2, () -> {
                    if (weave) {
                        boolean left = (ticks[0] / 20) % 2 == 0;
                        vehicle.controlUnit.left = left;
                        vehicle.controlUnit.right = !left;
                    }
                    ticks[0]++;
                    float r = Math.abs(Mth.wrapDegrees(vehicle.getZRot()));
                    roll[0] = Math.max(roll[0], r);
                    if (flipped[0] < 0 && (r > 70 || Math.abs(vehicle.getXRot()) > 70)) {
                        flipped[0] = ticks[0];
                    }
                    if (Boolean.getBoolean("combinedassault.bench.trace") && ticks[0] % 5 == 0) {
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[terracetrace] %s t%d x %.2f z %.2f y %.3f pitch %.1f roll %.1f v %.3f,%.3f share %.2f",
                                test, ticks[0], vehicle.getX() - x0, vehicle.getZ() - start.getZ(), vehicle.getY() - floor,
                                vehicle.getXRot(), vehicle.getZRot(), vehicle.getDeltaMovement().horizontalDistance(),
                                vehicle.getDeltaMovement().y, vehicle.physicsEngine.tractionShare()));
                    }
                })
                .thenExecute(() -> {
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[terrace] %-24s %-7s flipTick %d maxRoll %.1f travelled %.1f",
                            test, flipped[0] >= 0 ? "FLIPPED" : "UPRIGHT", flipped[0], roll[0], Math.abs(vehicle.getX() - x0)));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, false);
                    setChunks(level, start, false);
                })
                .thenSucceed();
    }

    private static void run(GameTestHelper helper, String test, String name, int angle, boolean rest, int lane) {
        ServerLevel level = helper.getLevel();
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation(name));
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[terrace] {} absent", test);
            helper.succeed();
            return;
        }
        BlockPos start = new BlockPos(LANE_X + lane * LANE_PITCH, helper.absolutePos(BlockPos.ZERO).getY() - 1, 3000);
        setChunks(level, start, true);
        build(level, start, true);
        double floor = start.getY() + 1;
        float yaw = angle;
        double dirX = -Mth.sin(yaw * Mth.DEG_TO_RAD), dirZ = Mth.cos(yaw * Mth.DEG_TO_RAD);
        AbstractVehicle vehicle = data.get().construct(level, new Vec3(start.getX() + 0.5, floor + 0.5, start.getZ() - 12), 0, yaw);
        vehicle.setDisplayId(CombinedAssault.modLocation(name));
        level.addFreshEntity(vehicle);
        vehicle.physicsEngine.canDestroyBlock = false;
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] top = {Double.NEGATIVE_INFINITY};
        int[] reached = {-1};
        helper.startSequence()
                .thenExecute(() -> {
                    // Front corner at the first face (rest) or RUN_UP blocks back along the heading.
                    var obb = vehicle.getMainCubeOBB().obb();
                    double half = Math.abs(dirZ) * obb.extents().z + Math.abs(dirX) * obb.extents().x;
                    double back = (rest ? 0.2 : RUN_UP) + half / Math.max(Math.abs(dirZ), 0.3);
                    vehicle.setPos(start.getX() + 0.5 - dirX * back, floor + 0.5, start.getZ() - dirZ * back);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    double rise = vehicle.getY() - floor;
                    top[0] = Math.max(top[0], rise);
                    if (reached[0] < 0 && rise > STEPS - 0.3) {
                        reached[0] = vehicle.tickCount;
                        vehicle.controlUnit.forward = false;
                    }
                    if (Boolean.getBoolean("combinedassault.bench.trace") && vehicle.tickCount % 5 == 0) {
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[terracetrace] %s t%d z %.2f y %.3f pitch %.1f roll %.1f v %.3f,%.3f share %.2f grounded %d",
                                test, vehicle.tickCount, vehicle.getZ() - start.getZ(), rise, vehicle.getXRot(), vehicle.getZRot(),
                                vehicle.getDeltaMovement().horizontalDistance(), vehicle.getDeltaMovement().y,
                                vehicle.physicsEngine.tractionShare(),
                                vehicle.getSuspensionUnits().stream().mapToInt(u -> u.isGrounded() ? u.contactCount() : 0).sum()));
                    }
                })
                .thenExecute(() -> {
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[terrace] %-24s %-7s top %.2f final %.2f z %.2f pitch %.1f roll %.1f v %.3f",
                            test, reached[0] >= 0 ? "CROSSED" : "STUCK", top[0], vehicle.getY() - floor, vehicle.getZ() - start.getZ(),
                            vehicle.getXRot(), vehicle.getZRot(), vehicle.getDeltaMovement().horizontalDistance()));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, false);
                    setChunks(level, start, false);
                })
                .thenSucceed();
    }

    private static int height(int z) {
        return z < 0 ? 0 : Math.min(STEPS, z / TREAD + 1);
    }

    private static void build(ServerLevel level, BlockPos start, boolean place) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -BEFORE; z <= AFTER; z++) {
                int top = place ? height(z) : STEPS;
                level.setBlock(start.offset(x, 0, z), (place ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                for (int y = 1; y <= top; y++) {
                    level.setBlock(start.offset(x, y, z), (place ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
    }

    private static void setChunks(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - BEFORE) >> 4; cz <= (start.getZ() + AFTER) >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
