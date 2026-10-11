package com.wf.combinedassault.vehicle.part.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Obstacle climb matrix; one {@code [bench]} log row per (vehicle, height).
 * Source compiles unchanged against upstream mainline: {@code tools/bench.sh} runs both trees.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class ClimbBench {

    /** Bench-only gametest namespace: {@code -Pbench} enables it instead of the mod's, so the suite stays out. */
    public static final String NAMESPACE = "combinedassaultbench";

    private static final String[] VEHICLES = {"humvee", "m1129", "t90", "m1a2", "m983"};
    /** Obstacle heights in blocks; x.5 = stone + bottom slab. */
    private static final double[] HEIGHTS = {1, 1.5, 2};
    private static final int LANE_Z = 2000;
    private static final int LANE_PITCH = 100;
    private static final int HALF_WIDTH = 4;
    private static final int RUN_UP = 16;
    private static final int DEPTH = 40;
    private static final int SETTLE_TICKS = 40;
    private static final int DRIVE_TICKS = 160;
    /** Hull rear this far past the face => measured, throttle off. getBoundingBox() < hull. */
    private static final double ON_TOP = 0.5;

    @GameTestGenerator
    public static List<TestFunction> climbBench() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (double height : HEIGHTS) {
                if (!(vehicle + "_" + height).contains(System.getProperty("combinedassault.bench.filter", "")) && !"climbbench".equals(System.getProperty("combinedassault.bench.filter", ""))) {
                    continue;
                }
                int at = lane++;
                String name = String.format(Locale.ROOT, "climbbench_%s_%s", vehicle, Double.toString(height).replace('.', '_'));
                tests.add(new TestFunction("climbbench", name, ClimbBench.NAMESPACE + ":empty",
                        SETTLE_TICKS + DRIVE_TICKS + 100, 0, false, helper -> run(helper, vehicle, height, at)));
            }
        }
        return tests;
    }

    private static void run(GameTestHelper helper, String name, double height, int lane) {
        ServerLevel level = helper.getLevel();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[bench] {} h={} absent", name, height);
            helper.succeed();
            return;
        }
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * LANE_PITCH);
        setChunks(level, start, true);
        build(level, start, height, true);
        double floor = start.getY() + 1;
        AbstractVehicle vehicle = data.get().construct(level, new Vec3(start.getX() + 0.5, floor + 0.5, start.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        // After add (initData resets it). Step limits, not rams: a rammed notch turns a 2-block wall into two 1-block
        // steps (M1A2 broke 6 face blocks).
        vehicle.physicsEngine.canDestroyBlock = false;
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] rest = new double[1];
        double[] peak = {Double.NEGATIVE_INFINITY};
        float[] pitch = new float[1];
        double[] rise = {Double.NaN};
        double[] past = new double[1];
        double face = start.getZ() + RUN_UP;
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    rest[0] = vehicle.getY();
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    if (Boolean.getBoolean("combinedassault.bench.trace") && vehicle.tickCount % 4 == 0) {
                        var hull = vehicle.getMainCubeOBB().obb();
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[climbtrace] %s h=%.1f t%d front %.2f bottom %.3f pitch %.1f v %.3f,%.3f grounded %d",
                                name, height, vehicle.tickCount, hull.centerZ() + hull.extents().z - face,
                                hull.centerY() - hull.extents().y - floor, vehicle.getXRot(),
                                vehicle.getDeltaMovement().horizontalDistance(), vehicle.getDeltaMovement().y,
                                vehicle.getSuspensionUnits().stream().mapToInt(u -> u.isGrounded() ? u.contactCount() : 0).sum()));
                    }
                    peak[0] = Math.max(peak[0], vehicle.getY() - rest[0]);
                    pitch[0] = Math.max(pitch[0], Math.abs(vehicle.getXRot()));
                    past[0] = vehicle.getZ() - face;
                    if (Double.isNaN(rise[0]) && vehicle.getZ() - vehicle.getMainCubeOBB().obb().extents().z > face + ON_TOP) {
                        rise[0] = vehicle.getY() - rest[0];
                        vehicle.controlUnit.forward = false;
                    }
                })
                .thenExecute(() -> {
                    boolean over = !Double.isNaN(rise[0]);
                    String outcome = !over ? (past[0] < 0 ? "STOPPED" : "STUCK") : rise[0] > height - 0.3 ? "CLIMBED" : "OTHER";
                    int broken = 0;
                    for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
                        for (int z = RUN_UP; z <= RUN_UP + DEPTH; z++) {
                            for (int y = 1; y <= Math.floor(height); y++) {
                                broken += level.getBlockState(start.offset(x, y, z)).isAir() ? 1 : 0;
                            }
                        }
                    }
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[bench] %-10s h=%.1f %-7s rise=%6.3f peak=%6.3f hop=%6.3f past=%6.2f maxPitch=%5.1f broken=%d",
                            name, height, outcome, over ? rise[0] : vehicle.getY() - rest[0], peak[0],
                            peak[0] - height, past[0], pitch[0], broken));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, height, false);
                    setChunks(level, start, false);
                })
                .thenSucceed();
    }

    private static void build(ServerLevel level, BlockPos start, double height, boolean place) {
        int full = (int) Math.floor(height);
        boolean slab = height - full > 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState half = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -RUN_UP; z <= RUN_UP + DEPTH; z++) {
                boolean obstacle = z >= RUN_UP;
                int top = obstacle ? full : 0;
                for (int y = 0; y <= top; y++) {
                    level.setBlockAndUpdate(start.offset(x, y, z), place ? stone : air);
                }
                if (obstacle && slab) {
                    level.setBlockAndUpdate(start.offset(x, full + 1, z), place ? half : air);
                }
            }
        }
    }

    private static void setChunks(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - RUN_UP) >> 4; cz <= (start.getZ() + RUN_UP + DEPTH) >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
