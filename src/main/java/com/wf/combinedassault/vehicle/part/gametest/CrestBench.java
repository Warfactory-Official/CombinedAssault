package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stepped pyramid (1-block steps, 1x1 crest, {@code top} high) driven straight through its crest from 8 headings; one
 * {@code [crest]} row per (vehicle, top, heading): crossed, stalled ticks, hung ticks (no wheel grounded, not falling),
 * peak. {@code -Pbench=crest}.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class CrestBench {

    static final String[] VEHICLES = {"humvee", "m983", "centauro", "m1129", "m1a2"};
    private static final int[] TOPS = {2, 3};
    private static final int HEADINGS = 8;
    private static final int LANE_X = 15000;
    private static final int LANE_PITCH = 48;
    private static final int APPROACH = 14;
    private static final int FLOOR = 18;
    private static final int SETTLE_TICKS = 30;
    private static final int DRIVE_TICKS = 400;
    private static final boolean TRACE = Boolean.getBoolean("combinedassault.bench.trace");
    private static final double STEER_GAIN = 10;
    private static final float STEER_DEADBAND = 3;

    @GameTestGenerator
    public static List<TestFunction> crestBench() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (int top : TOPS) {
                for (int h = 0; h < HEADINGS; h++) {
                    float heading = h * 360F / HEADINGS;
                    String name = String.format(Locale.ROOT, "crest_%s_%d_%03d", vehicle, top, (int) heading);
                    if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                        continue;
                    }
                    int at = lane++;
                    tests.add(new TestFunction("crest", name, ClimbBench.NAMESPACE + ":empty",
                            SETTLE_TICKS + DRIVE_TICKS + 600, 0, false, helper -> run(helper, vehicle, top, heading, at)));
                }
            }
        }
        return tests;
    }

    private static void run(GameTestHelper helper, String name, int top, float heading, int lane) {
        ServerLevel level = helper.getLevel();
        ResourceLocation id = CombinedAssault.modLocation(name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[crest] {} absent", name);
            helper.succeed();
            return;
        }
        BlockPos centre = new BlockPos(LANE_X + lane * LANE_PITCH, helper.absolutePos(BlockPos.ZERO).getY() - 1, 0);
        chunks(level, centre, true);
        build(level, centre, top, true);
        double rad = Math.toRadians(heading);
        Vec3 dir = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
        Vec3 mid = Vec3.atBottomCenterOf(centre.above());
        Vec3 spawn = mid.subtract(dir.scale(APPROACH)).add(0, 0.6, 0);
        AbstractVehicle[] v = new AbstractVehicle[1];
        ArmorStand[] driver = new ArmorStand[1];
        int[] ticks = new int[4];
        double[] peak = {Double.NEGATIVE_INFINITY, 0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(centre)
                        && level.isPositionEntityTicking(BlockPos.containing(spawn)), "lane not entity-ticking yet"))
                .thenExecute(() -> {
                    v[0] = data.get().construct(level, spawn, 0, heading);
                    v[0].setDisplayId(id);
                    level.addFreshEntity(v[0]);
                    driver[0] = new ArmorStand(level, spawn.x, spawn.y, spawn.z);
                    driver[0].setNoGravity(true);
                    level.addFreshEntity(driver[0]);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v[0];
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver[0]);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    AbstractVehicle vehicle = v[0];
                    Vec3 rel = vehicle.position().subtract(mid);
                    double along = rel.dot(dir);
                    double lateral = rel.x * Math.cos(rad) + rel.z * Math.sin(rad);
                    vehicle.controlUnit.forward = along < APPROACH;
                    float target = heading + (float) Mth.clamp(lateral * STEER_GAIN, -30, 30);
                    float error = Mth.wrapDegrees(vehicle.getYRot() - target);
                    vehicle.controlUnit.left = error > STEER_DEADBAND;
                    vehicle.controlUnit.right = error < -STEER_DEADBAND;
                    long grounded = vehicle.getSuspensionUnits().stream().filter(SuspensionUnit::isGrounded).count();
                    double speed = vehicle.getDeltaMovement().horizontalDistance();
                    boolean onPile = Math.abs(along) < top + 2 && Math.abs(lateral) < top + 2;
                    if (vehicle.controlUnit.forward && speed < 0.01) {
                        ticks[0]++;
                    }
                    if (onPile && grounded == 0 && Math.abs(vehicle.getDeltaMovement().y) < 0.02) {
                        ticks[1]++;
                    }
                    ticks[2]++;
                    if (vehicle.getY() - mid.y > peak[0]) {
                        peak[0] = vehicle.getY() - mid.y;
                    }
                    peak[1] = along;
                    if (TRACE && ticks[2] % 10 == 0) {
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[cresttrace] %s/%d/%03d t%d along %.2f lat %.2f y %.3f pitch %.1f roll %.1f v %.3f,%.3f grounded %d/%d",
                                name, top, (int) heading, ticks[2], along, lateral, vehicle.getY() - mid.y,
                                vehicle.getXRot(), vehicle.getZRot(), speed, vehicle.getDeltaMovement().y, grounded,
                                vehicle.getSuspensionUnits().size()));
                        StringBuilder gaps = new StringBuilder();
                        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
                            unit.probeBottoms(1, false, p -> gaps.append(String.format(Locale.ROOT, " %.2f",
                                    p.y - level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z)))));
                            gaps.append(unit.isGrounded() ? "*" : "").append(String.format(Locale.ROOT, " min %.2f |", unit.getMinCompression()));
                        }
                        CombinedAssault.LOGGER.info("[cresttrace]   wheel bottom - column top:{}", gaps);
                    }
                })
                .thenExecute(() -> {
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[crest] %-9s top %d heading %3d stalled %3d hung %3d /%d peak %.2f along %6.1f %s",
                            name, top, (int) heading, ticks[0], ticks[1], ticks[2], peak[0], peak[1],
                            peak[1] > top + 4 ? "CROSSED" : "STOPPED"));
                    driver[0].discard();
                    v[0].discard();
                    build(level, centre, top, false);
                    chunks(level, centre, false);
                })
                .thenSucceed();
    }

    private static void build(ServerLevel level, BlockPos centre, int top, boolean place) {
        for (int x = -FLOOR; x <= FLOOR; x++) {
            for (int z = -FLOOR; z <= FLOOR; z++) {
                int h = Math.max(0, top - Math.max(Math.abs(x), Math.abs(z)));
                level.setBlock(centre.offset(x, 0, z), (place ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                for (int y = 1; y <= top; y++) {
                    level.setBlock(centre.offset(x, y, z), place && y <= h ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void chunks(ServerLevel level, BlockPos centre, boolean forced) {
        for (int cx = (centre.getX() - FLOOR) >> 4; cx <= (centre.getX() + FLOOR) >> 4; cx++) {
            for (int cz = (centre.getZ() - FLOOR) >> 4; cz <= (centre.getZ() + FLOOR) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}
