package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.TrackedVehicle;
import com.wf.combinedassault.vehicle.entity.WheeledVehicle;
import com.wf.wflib.testkit.api.ForcedChunks;
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

import java.util.Locale;

/**
 * Full throttle at a 1-block ledge, heading 45 deg off its normal ({@code flat}), or along the normal of a ledge whose
 * edge is a block zigzag. The grip model (wheelspin / track_slip) must not cost climb time or add stall ticks against the
 * same vehicle with the model removed; the zigzag must not stall (needs the wflib {@code DriveSurface.View.covers}
 * anti-diagonal probes: a convex plateau corner read as a wall, 26-43 stalled ticks).
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class DiagonalStepGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_Z = 1500;
    private static final int LANE_PITCH = 100;
    private static final int HALF_WIDTH = 24;
    private static final int RUN_UP = 16;
    private static final int DEPTH = 40;
    private static final int SETTLE = 40;
    private static final int DRIVE_TICKS = 220;
    /** Below this a driven vehicle counts as stopped, blocks/tick. */
    private static final double STALL_SPEED = 0.03;
    private static final int TOLERANCE_TICKS = 8;
    private static final int MAX_ZIGZAG_STALL = 10;

    private static final class Run {
        AbstractVehicle vehicle;
        ArmorStand driver;
        int ticks = -1;
        int stalled;
        int tick;
        double face;
        double rest;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + DRIVE_TICKS + 100)
    public static void aSprungTruckClimbsADiagonalStepAsFastWithGripModel(GameTestHelper helper) {
        compare(helper, "humvee", 0, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + DRIVE_TICKS + 100)
    public static void aTankClimbsADiagonalStepAsFastWithGripModel(GameTestHelper helper) {
        compare(helper, "m1a2", 1, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + DRIVE_TICKS + 100)
    public static void aSixWheelerClimbsADiagonalStepAsFastWithGripModel(GameTestHelper helper) {
        compare(helper, "m983", 2, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + DRIVE_TICKS + 100)
    public static void aSprungTruckClimbsAZigzagLedgeAsFastWithGripModel(GameTestHelper helper) {
        compare(helper, "humvee", 3, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + DRIVE_TICKS + 100)
    public static void aTankClimbsAZigzagLedgeAsFastWithGripModel(GameTestHelper helper) {
        compare(helper, "m1a2", 4, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + DRIVE_TICKS + 100)
    public static void aSixWheelerClimbsAZigzagLedgeAsFastWithGripModel(GameTestHelper helper) {
        compare(helper, "m983", 5, true);
    }

    private static void compare(GameTestHelper helper, String name, int lane, boolean zigzag) {
        ServerLevel level = helper.getLevel();
        BlockPos on = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * 2 * LANE_PITCH);
        BlockPos off = on.offset(0, 0, LANE_PITCH);
        Run with = new Run();
        Run without = new Run();
        for (BlockPos start : new BlockPos[]{on, off}) {
            force(level, start, true);
            build(level, start, true, zigzag);
        }
        helper.startSequence()
                .thenExecute(() -> {
                    spawn(level, name, on, with, true, zigzag);
                    spawn(level, name, off, without, false, zigzag);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    for (Run run : new Run[]{with, without}) {
                        run.rest = run.vehicle.getY();
                        run.vehicle.setEnergy(run.vehicle.energyInfo.energyCapacity);
                        run.vehicle.controlUnit.setOperator(run.driver);
                        run.vehicle.toggleEngine(true);
                        run.vehicle.controlUnit.forward = true;
                    }
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    sample(with, zigzag);
                    sample(without, zigzag);
                })
                .thenExecute(() -> {
                    String report = String.format(Locale.ROOT, (zigzag ? "zigzag " : "flat ") + "%s: model on %d ticks / %d stalled; off %d / %d",
                            name, with.ticks, with.stalled, without.ticks, without.stalled);
                    CombinedAssault.LOGGER.info("[diagstep] {}", report);
                    for (Run run : new Run[]{with, without}) {
                        run.driver.discard();
                        run.vehicle.discard();
                    }
                    for (BlockPos start : new BlockPos[]{on, off}) {
                        build(level, start, false, zigzag);
                        force(level, start, false);
                    }
                    if (zigzag && with.stalled > MAX_ZIGZAG_STALL) {
                        helper.fail("zigzag ledge stalls the vehicle (wflib covers fix missing?): " + report);
                    } else if (without.ticks < 0) {
                        helper.fail("baseline never climbed: " + report);
                    } else if (with.ticks < 0 || with.ticks > without.ticks + TOLERANCE_TICKS
                            || with.stalled > without.stalled + TOLERANCE_TICKS) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    private static void spawn(ServerLevel level, String name, BlockPos start, Run run, boolean model, boolean zigzag) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        run.vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 0.5), 0, 45);
        run.vehicle.setDisplayId(id);
        level.addFreshEntity(run.vehicle);
        run.vehicle.physicsEngine.canDestroyBlock = false;
        if (!model) {
            if (run.vehicle instanceof WheeledVehicle wheeled) {
                wheeled.wheelspin = null;
            } else if (run.vehicle instanceof TrackedVehicle tracked) {
                tracked.trackSlip = null;
            }
        }
        run.driver = new ArmorStand(level, run.vehicle.getX(), run.vehicle.getY(), run.vehicle.getZ());
        run.driver.setNoGravity(true);
        level.addFreshEntity(run.driver);
        run.face = zigzag ? start.getZ() - start.getX() + RUN_UP : start.getZ() + RUN_UP;
    }

    /** Over = hull centre past the face and up on the ledge. */
    private static void sample(Run run, boolean zigzag) {
        if (run.ticks >= 0) {
            return;
        }
        double speed = run.vehicle.getDeltaMovement().horizontalDistance();
        // Zigzag ledge: boundary z - x = face, normal along the heading (yaw 45); flat: z = face.
        double past = zigzag ? (run.vehicle.getZ() - run.vehicle.getX() - run.face) / Math.sqrt(2) : run.vehicle.getZ() - run.face;
        boolean near = Math.abs(past) < 4;
        if (near && speed < STALL_SPEED) {
            run.stalled++;
        }
        if (past > 3 && run.vehicle.getY() - run.rest > 0.7) {
            run.ticks = run.tick;
            run.vehicle.controlUnit.forward = false;
        }
        run.tick++;
    }

    private static void build(ServerLevel level, BlockPos start, boolean place, boolean zigzag) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -RUN_UP; z <= RUN_UP + DEPTH; z++) {
                int top = (zigzag ? z - x >= RUN_UP : z >= RUN_UP) ? 1 : 0;
                for (int y = 0; y <= top; y++) {
                    level.setBlockAndUpdate(start.offset(x, y, z), (place ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                }
            }
        }
    }

    private static void force(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - RUN_UP) >> 4; cz <= (start.getZ() + RUN_UP + DEPTH) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}
