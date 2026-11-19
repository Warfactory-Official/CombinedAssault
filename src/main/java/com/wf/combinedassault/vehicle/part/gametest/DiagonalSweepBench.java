package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.testkit.api.ForcedChunks;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1-block ledge whose edge runs at world angle {@code alpha} (block staircase of any tooth ratio), approached with the
 * heading {@code phi} off the edge normal; one {@code [diagsweep]} row per (vehicle, alpha, phi).
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class DiagonalSweepBench {

    private static final String[] VEHICLES = {"humvee", "m1a2"};
    /** Edge normal, world degrees (0 = face toward +z, grid-aligned). */
    private static final double[] ALPHAS = {0, 15, 26.565, 45, 63.435, 75};
    /** Heading off the edge normal, degrees. */
    private static final double[] PHIS = {0, 20, 45};
    private static final int LANE_Z = 4000;
    private static final int LANE_PITCH = 120;
    private static final int HALF = 36;
    private static final int RUN_UP = 14;
    private static final int SETTLE = 40;
    private static final int DRIVE_TICKS = 240;
    private static final double STALL_SPEED = 0.03;

    @GameTestGenerator
    public static List<TestFunction> diagSweep() {
        List<TestFunction> tests = new ArrayList<>();
        String filter = System.getProperty("combinedassault.bench.filter", "");
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (double alpha : ALPHAS) {
                for (double phi : PHIS) {
                    String name = String.format(Locale.ROOT, "diagsweep_%s_a%d_p%d", vehicle, Math.round(alpha), Math.round(phi));
                    if (!name.contains(filter) && !"diagsweep".equals(filter)) {
                        continue;
                    }
                    int at = lane++;
                    tests.add(new TestFunction("diagsweep", name, ClimbBench.NAMESPACE + ":empty", SETTLE + DRIVE_TICKS + 100, 0, false,
                            helper -> run(helper, vehicle, alpha, phi, at)));
                }
            }
        }
        return tests;
    }

    private static void run(GameTestHelper helper, String name, double alpha, double phi, int lane) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * LANE_PITCH);
        double ar = Math.toRadians(alpha);
        double nx = -Math.sin(ar), nz = Math.cos(ar);
        double yaw = alpha + phi;
        double hx = -Math.sin(Math.toRadians(yaw)), hz = Math.cos(Math.toRadians(yaw));
        double px = start.getX() + 0.5 + hx * RUN_UP, pz = start.getZ() + 0.5 + hz * RUN_UP;
        for (int cx = (start.getX() - HALF) >> 4; cx <= (start.getX() + HALF) >> 4; cx++) {
            for (int cz = (start.getZ() - HALF) >> 4; cz <= (start.getZ() + HALF + 20) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, true);
            }
        }
        build(level, start, px, pz, nx, nz, true);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 0.5), 0, (float) yaw);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        vehicle.physicsEngine.canDestroyBlock = false;
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] rest = new double[1];
        int[] ticks = {-1, 0, 0};
        double[] minNear = {Double.MAX_VALUE};
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    rest[0] = vehicle.getY();
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    if (ticks[0] >= 0) {
                        return;
                    }
                    double past = (vehicle.getX() - px) * nx + (vehicle.getZ() - pz) * nz;
                    double speed = vehicle.getDeltaMovement().horizontalDistance();
                    if (Math.abs(past) < 4) {
                        minNear[0] = Math.min(minNear[0], speed);
                        if (speed < STALL_SPEED) {
                            ticks[1]++;
                        }
                    }
                    if (past > 3 && vehicle.getY() - rest[0] > 0.7) {
                        ticks[0] = ticks[2];
                        vehicle.controlUnit.forward = false;
                    }
                    ticks[2]++;
                })
                .thenExecute(() -> {
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT, "[diagsweep] %-7s alpha %4.1f phi %2.0f %s ticks %3d stalled %3d minNear %.3f",
                            name, alpha, phi, ticks[0] < 0 ? "STUCK  " : "CLIMBED", ticks[0], ticks[1], minNear[0]));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, px, pz, nx, nz, false);
                    for (int cx = (start.getX() - HALF) >> 4; cx <= (start.getX() + HALF) >> 4; cx++) {
                        for (int cz = (start.getZ() - HALF) >> 4; cz <= (start.getZ() + HALF + 20) >> 4; cz++) {
                            ForcedChunks.set(level, cx, cz, false);
                        }
                    }
                })
                .thenSucceed();
    }

    private static void build(ServerLevel level, BlockPos start, double px, double pz, double nx, double nz, boolean place) {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF + 20; z++) {
                boolean plateau = (start.getX() + x + 0.5 - px) * nx + (start.getZ() + z + 0.5 - pz) * nz >= 0;
                for (int y = 0; y <= (plateau ? 1 : 0); y++) {
                    level.setBlockAndUpdate(start.offset(x, y, z), (place ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                }
            }
        }
    }
}
