package com.wf.combinedassault.vehicle.part.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Full throttle over seeded 0..2-block value-noise terrain; one {@code [rough]} row per (vehicle, seed, steer):
 * worst tilt, flip tick. Flip rate across seeds; {@link TerrainGameTest} gates one seed. {@code -Pbench=rough}.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class RoughBench {

    // TODO m1129 (SSF 1.1 half-track / 1.2 CoM = 0.92) rolls on WEAVE/LOCK and rough lanes, airborne off bumps;
    // pre-audit physics identical. Back in once its data or the bump response is fixed.
    private static final String[] VEHICLES = {"humvee", "humvee_mk19", "centauro", "m983", "m1a2", "t90"};
    private static final int SEEDS = 8;
    private static final int LANE_X = -3000;
    private static final int LANE_PITCH = 40;
    private static final int HALF_WIDTH = 10;
    private static final int LENGTH = 110;
    /** Noise cell, blocks. */
    private static final int CELL = 4;
    private static final int SETTLE_TICKS = 30;
    private static final int DRIVE_TICKS = 240;
    /** Steering flips this often in weave runs. */
    private static final int WEAVE = 25;

    @GameTestGenerator
    public static List<TestFunction> roughBench() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (int seed = 0; seed < SEEDS; seed++) {
                for (boolean weave : new boolean[]{false, true}) {
                    String name = String.format(Locale.ROOT, "rough_%s_%d_%s", vehicle, seed, weave ? "weave" : "straight");
                    if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                        continue;
                    }
                    int at = lane++, s = seed;
                    tests.add(new TestFunction("roughbench", name, ClimbBench.NAMESPACE + ":empty",
                            SETTLE_TICKS + DRIVE_TICKS + 100, 0, false, helper -> run(helper, vehicle, s, weave, at)));
                }
            }
        }
        return tests;
    }

    /** Gate's ROUGH/WEAVE courses, identical terrain, 12 world lanes each: flip rate vs lane-only (float) perturbation. {@code -Pbench=terrainsweep}. */
    @GameTestGenerator
    public static List<TestFunction> terrainSweep() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 100;
        for (String vehicle : new String[]{"humvee", "humvee_mk19", "m983"}) {
            for (TerrainGameTest.Course course : new TerrainGameTest.Course[]{TerrainGameTest.Course.ROUGH, TerrainGameTest.Course.WEAVE}) {
                for (int k = 0; k < 12; k++) {
                    String name = String.format(Locale.ROOT, "terrainsweep_%s_%s_%d", vehicle, course.name().toLowerCase(Locale.ROOT), k);
                    if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                        continue;
                    }
                    int at = lane++;
                    tests.add(new TestFunction("terrainsweep", name, ClimbBench.NAMESPACE + ":empty",
                            30 + TerrainGameTest.drive(course) + 100, 0, false,
                            helper -> TerrainGameTest.run(helper, vehicle, course, at)));
                }
            }
        }
        return tests;
    }

    private static int height(int seed, int x, int z) {
        double fx = (double) x / CELL, fz = (double) z / CELL;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = hash(seed, x0, z0), b = hash(seed, x0 + 1, z0), c = hash(seed, x0, z0 + 1), d = hash(seed, x0 + 1, z0 + 1);
        double v = (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
        return (int) Math.floor(v * 3);
    }

    private static double hash(int seed, int x, int z) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static void run(GameTestHelper helper, String vehicleId, int seed, boolean weave, int lane) {
        String name = String.format(Locale.ROOT, "%s_%d_%s", vehicleId, seed, weave ? "weave" : "straight");
        com.wf.combinedassault.all.AllConfigs.Cached.asyncVehiclePhysics = System.getProperty("combinedassault.bench.filter", "").contains("async");
        ServerLevel level = helper.getLevel();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, vehicleId);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[rough] {} absent", name);
            helper.succeed();
            return;
        }
        // World-fixed lanes: arena-relative ones overlapped across arenas (cleanup erased a neighbour's course).
        BlockPos start = new BlockPos(LANE_X - lane * LANE_PITCH, helper.absolutePos(BlockPos.ZERO).getY() - 1, 0);
        setChunks(level, start, true);
        build(level, start, seed, true);
        double ground = start.getY() + 1 + height(seed, 0, 4);
        AbstractVehicle vehicle = data.get().construct(level, new Vec3(start.getX() + 0.5, ground + 0.6, start.getZ() + 4.5), 0, 0);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        float[] worst = new float[2];
        int[] flip = {-1};
        int[] tick = {0};
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    int t = tick[0]++;
                    if (weave) {
                        vehicle.controlUnit.left = (t / WEAVE) % 2 == 0;
                        vehicle.controlUnit.right = !vehicle.controlUnit.left;
                    }
                    if (vehicle.getZ() > start.getZ() + LENGTH - 8) {
                        vehicle.controlUnit.forward = false;
                    }
                    Vector3f up = vehicle.getMainCubeOBB().obb().getAxes()[1];
                    worst[0] = Math.max(worst[0], Math.abs(vehicle.getXRot()));
                    worst[1] = Math.max(worst[1], Math.abs(vehicle.getZRot()));
                    if (flip[0] < 0 && up.y < 0.2f) {
                        flip[0] = t;
                    }
                })
                .thenExecute(() -> {
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[rough] %-28s %-7s flipTick=%4d maxPitch=%5.1f maxRoll=%5.1f travelled=%6.1f",
                            name, flip[0] >= 0 ? "FLIPPED" : "upright", flip[0], worst[0], worst[1], vehicle.getZ() - start.getZ() - 4.5));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, seed, false);
                    setChunks(level, start, false);
                })
                .thenSucceed();
    }

    private static void build(ServerLevel level, BlockPos start, int seed, boolean place) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -4; z <= LENGTH; z++) {
                int top = height(seed, x, z);
                for (int y = 0; y <= top; y++) {
                    level.setBlockAndUpdate(start.offset(x, y, z), place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void setChunks(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - 4) >> 4; cz <= (start.getZ() + LENGTH) >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
