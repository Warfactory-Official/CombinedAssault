package com.wf.combinedassault.vehicle.part.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.structure.ConvexHull;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stair hill, 1 block per {@code run} blocks; one {@code [hillclip]} row per (vehicle, run): hull vertex depth below the
 * column surface (max, per-tick mean, ticks > 0.1), bottomed wheel depth, peak. {@code -Pbench=hillclip}.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class HillClipBench {

    static final String[] VEHICLES = {"humvee", "m983", "m1129", "centauro", "mim104", "m1a2", "t84", "t90", "merkava"};
    private static final int[] RUNS = {1, 2, 3, 4};
    private static final int LANE_X = 9000;
    private static final int LANE_PITCH = 24;
    private static final int HALF_WIDTH = 8;
    private static final int START = 24;
    private static final int TOP = 6;
    private static final int PLATEAU = 16;
    private static final int SETTLE_TICKS = 30;
    private static final int DRIVE_TICKS = 600;
    private static final boolean TRACE = Boolean.getBoolean("combinedassault.bench.trace");
    /** Degrees of target yaw per block off the centre line; deadband degrees. */
    private static final double STEER_GAIN = 10;
    private static final float STEER_DEADBAND = 3;

    @GameTestGenerator
    public static List<TestFunction> hillClipBench() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (int run : RUNS) {
                String name = String.format(Locale.ROOT, "hillclip_%s_%d", vehicle, run);
                if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                    continue;
                }
                int at = lane++;
                tests.add(new TestFunction("hillclip", name, ClimbBench.NAMESPACE + ":empty",
                        SETTLE_TICKS + DRIVE_TICKS + 100, 0, false, helper -> run(helper, vehicle, run, at)));
            }
        }
        return tests;
    }

    private static int length(int run) {
        return START + 2 * TOP * run + PLATEAU + 30;
    }

    private static int height(int run, int z) {
        int up = (z - START) / run;
        int down = (START + 2 * TOP * run + PLATEAU - z) / run;
        return z < START ? 0 : Math.max(0, Math.min(TOP, Math.min(up, down)));
    }

    private static void run(GameTestHelper helper, String name, int run, int lane) {
        ServerLevel level = helper.getLevel();
        ResourceLocation id = CombinedAssault.modLocation(name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[hillclip] {} absent", name);
            helper.succeed();
            return;
        }
        BlockPos start = new BlockPos(LANE_X + lane * LANE_PITCH, helper.absolutePos(BlockPos.ZERO).getY() - 1, 0);
        setChunks(level, start, run, true);
        build(level, start, run, true);
        AbstractVehicle vehicle = data.get().construct(level,
                new Vec3(start.getX() + 0.5, start.getY() + 1.6, start.getZ() + 8.5), 0, 0);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] hull = new double[3];
        double[] wheel = new double[1];
        double[] peak = {Double.NEGATIVE_INFINITY};
        int[] ticks = new int[2];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    if (vehicle.getZ() > start.getZ() + length(run) - 12) {
                        vehicle.controlUnit.forward = false;
                    }
                    // Lane keeping: head back to the centre line (a driver steers; drift off the lane is not a climb result).
                    double offset = vehicle.getX() - (start.getX() + 0.5);
                    float error = Mth.wrapDegrees(vehicle.getYRot() - (float) Mth.clamp(offset * STEER_GAIN, -30, 30));
                    vehicle.controlUnit.left = error > STEER_DEADBAND;
                    vehicle.controlUnit.right = error < -STEER_DEADBAND;
                    double depth = 0;
                    String deepest = "";
                    Vector3f p = new Vector3f();
                    for (VehicleCubeOBB cube : vehicle.getVehicleCubeOBBs()) {
                        if (!cube.isSolid()) {
                            continue;
                        }
                        ConvexHull shape = cube.hull();
                        int n = shape == null ? 8 : shape.vertexCount();
                        for (int k = 0; k < n; k++) {
                            if (shape == null) {
                                p.set((float) ((k & 1) == 0 ? -cube.width : cube.width) / 2,
                                        (float) ((k & 2) == 0 ? -cube.height : cube.height) / 2,
                                        (float) ((k & 4) == 0 ? -cube.depth : cube.depth) / 2);
                            } else {
                                float[] pts = shape.points();
                                p.set(pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2]);
                            }
                            cube.rotation.transform(p);
                            double x = cube.position.x + p.x, y = cube.position.y + p.y, z = cube.position.z + p.z;
                            cursor.set(Math.floor(x), Math.floor(y), Math.floor(z));
                            if (level.getBlockState(cursor).isAir()) {
                                continue;
                            }
                            while (!level.getBlockState(cursor).isAir()) {
                                cursor.move(0, 1, 0);
                            }
                            if (cursor.getY() - y > depth) {
                                depth = cursor.getY() - y;
                                deepest = (cube == vehicle.getMainCubeOBB() ? "MAIN " : "") + cube.group.name + "/" + cube.tag();
                            }
                        }
                    }
                    for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
                        if (unit.isGrounded()) {
                            wheel[0] = Math.max(wheel[0], (unit.getContactCompression() - unit.getMaxCompression())
                                    * -unit.getSuspensionDirection().y);
                        }
                    }
                    if (TRACE && ticks[0] % 10 == 0) {
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[hilltrace] %s/%d t%d x %.2f z %.2f y %.3f yaw %.1f pitch %.1f v %.3f,%.3f depth %.3f %s grounded %s",
                                name, run, ticks[0], vehicle.getX() - start.getX() - 0.5, vehicle.getZ() - start.getZ(),
                                vehicle.getY() - start.getY() - 1, vehicle.getYRot(),
                                vehicle.getXRot(), vehicle.getDeltaMovement().horizontalDistance(), vehicle.getDeltaMovement().y,
                                depth, deepest, vehicle.getSuspensionUnits().stream().filter(SuspensionUnit::isGrounded).count()
                                        + "/" + vehicle.getSuspensionUnits().size()));
                    }
                    hull[0] = Math.max(hull[0], depth);
                    hull[1] += depth;
                    ticks[0]++;
                    if (depth > 0.1) {
                        ticks[1]++;
                    }
                    peak[0] = Math.max(peak[0], vehicle.getY() - start.getY() - 1);
                })
                .thenExecute(() -> {
                    double travelled = vehicle.getZ() - start.getZ() - 8.5;
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[hillclip] %-11s run %d hullMax %.3f hullMean %.3f deep %3d/%d wheelMax %.3f peak %.2f travelled %6.1f x %5.1f %s",
                            name, run, hull[0], hull[1] / Math.max(1, ticks[0]), ticks[1], ticks[0], wheel[0], peak[0],
                            travelled, vehicle.getX() - start.getX() - 0.5, travelled > length(run) - 30 ? "CROSSED" : "STOPPED"));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, run, false);
                    setChunks(level, start, run, false);
                })
                .thenSucceed();
    }

    private static void build(ServerLevel level, BlockPos start, int run, boolean place) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -4; z <= length(run); z++) {
                int top = place ? height(run, z) : TOP;
                level.setBlock(start.offset(x, 0, z), (place ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                for (int y = 1; y <= top; y++) {
                    level.setBlock(start.offset(x, y, z), place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void setChunks(ServerLevel level, BlockPos start, int run, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - 4) >> 4; cz <= (start.getZ() + length(run)) >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
