package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.util.OBB;

import java.util.Locale;
import java.util.Optional;

/** maxUpStep = 1: 1-block step climbed (peak < h + HOP); 2-block wall stops the vehicle (peak < 1). */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class ClimbGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_Z = 700;
    private static final int LANE_PITCH = 100;
    /** Floor half width (x) and run-up before the obstacle (z). */
    private static final int HALF_WIDTH = 4;
    private static final int RUN_UP = 16;
    private static final int DEPTH = 40;
    private static final int DRIVE_TICKS = 160;
    /** Hull rear this far past the face => measured, throttle off. */
    private static final double ON_TOP = 0.5;
    /**
     * Allowed peak above the step top. Measured 0.85-1.22 (M1A2, M983; arena coordinates vary per run).
     * Stacked-teleport bug: >= 1.8.
     */
    private static final double HOP = 1.5;

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aSprungTruckClimbsOneBlock(GameTestHelper helper) {
        run(helper, "humvee", 0, 1, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aSprungTruckStopsAtTwoBlocks(GameTestHelper helper) {
        run(helper, "humvee", 1, 2, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aTankClimbsOneBlock(GameTestHelper helper) {
        run(helper, "m1a2", 2, 1, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aTankStopsAtTwoBlocks(GameTestHelper helper) {
        run(helper, "m1a2", 3, 2, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aTractorClimbsOneBlock(GameTestHelper helper) {
        run(helper, "m983", 4, 1, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aTractorStopsAtTwoBlocks(GameTestHelper helper) {
        run(helper, "m983", 5, 2, false);
    }

    private static void run(GameTestHelper helper, String name, int lane, int height, boolean climbs) {
        ServerLevel level = helper.getLevel();
        ResourceLocation id = CombinedAssault.modLocation(name);
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * LANE_PITCH);
        int length = RUN_UP + DEPTH;
        setChunks(level, start, length, true);
        build(level, start, length, height, true);
        double floor = start.getY() + 1;
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return;
        }
        AbstractVehicle vehicle = data.get().construct(level, new Vec3(start.getX() + 0.5, floor + 0.5, start.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        // After add (initData resets it). Step limits, not rams: a rammed notch turns a 2-block wall into two 1-block
        // steps (M1A2 broke 6 face blocks).
        vehicle.physicsEngine.canDestroyBlock = false;
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] peak = {Double.NEGATIVE_INFINITY};
        double[] onTop = {Double.NaN};
        double face = start.getZ() + RUN_UP;
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    peak[0] = Math.max(peak[0], bottom(vehicle) - floor);
                    if (Double.isNaN(onTop[0]) && rear(vehicle) > face + ON_TOP) {
                        onTop[0] = bottom(vehicle) - floor;
                        vehicle.controlUnit.forward = false;
                    }
                })
                .thenExecute(() -> {
                    String report = String.format(Locale.ROOT,
                            "%s vs %d-block: bottom %.3f above floor once past (NaN = never), peak %.3f, centre %.2f past the face",
                            name, height, onTop[0], peak[0], vehicle.getZ() - face);
                    CombinedAssault.LOGGER.info("[climb] {}", report);
                    driver.discard();
                    vehicle.discard();
                    build(level, start, length, height, false);
                    setChunks(level, start, length, false);
                    boolean up = onTop[0] > height - 0.5 && peak[0] < height + HOP;
                    boolean stayed = peak[0] < 1.0;
                    if (climbs ? !up : !stayed) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    /** Hull, not getBoundingBox(): the entity box is smaller than the hull. Yaw 0 => +Z forward. */
    private static double rear(AbstractVehicle vehicle) {
        OBB hull = vehicle.getMainCubeOBB().obb();
        return hull.centerZ() - hull.extents().z;
    }

    private static double bottom(AbstractVehicle vehicle) {
        OBB hull = vehicle.getMainCubeOBB().obb();
        return hull.centerY() - hull.extents().y;
    }

    private static void build(ServerLevel level, BlockPos start, int length, int height, boolean place) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -RUN_UP; z <= length; z++) {
                int top = z >= RUN_UP ? height : 0;
                for (int y = 0; y <= top; y++) {
                    level.setBlockAndUpdate(start.offset(x, y, z),
                            place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void setChunks(ServerLevel level, BlockPos start, int length, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - RUN_UP) >> 4; cz <= (start.getZ() + length) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}
