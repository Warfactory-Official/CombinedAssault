package com.wf.combinedassault.vehicle.part.gametest;

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
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.Locale;
import java.util.Optional;

/** Sprung hulls tip past their support polygon, stay over, and cannot drive on their side (tumbling on). */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class TumbleGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRUCK = CombinedAssault.modLocation("centauro");
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");
    private static final int LANE_X = -600;
    private static final int LANE_PITCH = 24;
    private static final int HALF_WIDTH = 6;
    private static final int LANE_LENGTH = 24;
    /** cos 75 degrees: below => on its side or roof. */
    private static final double TIPPED_UP = 0.2588;
    private static final int SETTLE_TICKS = 200;

    @GameTest(template = TEMPLATE, timeoutTicks = 280)
    public static void aTruckTiltedShortOfTippingSettlesBack(GameTestHelper helper) {
        tilt(helper, 0, 20, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 280)
    public static void aTruckTiltedPastTippingFallsOnItsSide(GameTestHelper helper) {
        tilt(helper, 1, 75, true);
    }

    private static void tilt(GameTestHelper helper, int lane, float roll, boolean tips) {
        BlockPos start = lane(helper, lane);
        AbstractVehicle truck = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, start.getY() + 2.5, start.getZ() + 12.5));
        if (truck == null) {
            return;
        }
        truck.setZRot(roll);
        truck.updateOBBs();
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    double up = up(truck);
                    double spin = truck.physicsEngine.angularVelocity.length();
                    String report = String.format(Locale.ROOT, "truck released at %.0f degrees roll: up.y %.3f, spin %.4f rad/t, zRot %.1f",
                            roll, up, spin, truck.getZRot());
                    CombinedAssault.LOGGER.info("[tumble] {}", report);
                    release(helper, start, truck);
                    boolean over = up < TIPPED_UP;
                    boolean upright = up > 0.99;
                    if (tips ? !over : !upright) {
                        helper.fail(report);
                    } else if (spin > 0.01) {
                        helper.fail("not at rest: " + report);
                    }
                })
                .thenSucceed();
    }

    /** Selfrighting snap off with tumbling on; wheels off the ground => no drive. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aTruckOnItsSideStaysOverAndCannotDrive(GameTestHelper helper) {
        BlockPos start = lane(helper, 2);
        AbstractVehicle truck = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, start.getY() + 3, start.getZ() + 12.5));
        if (truck == null) {
            return;
        }
        truck.setZRot(90);
        truck.updateOBBs();
        ServerLevel level = helper.getLevel();
        ArmorStand driver = new ArmorStand(level, truck.getX(), truck.getY(), truck.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        Vec3[] parked = new Vec3[1];
        double[] settled = new double[1];
        helper.startSequence()
                .thenIdle(80)
                .thenExecute(() -> {
                    settled[0] = up(truck);
                    parked[0] = truck.position();
                    truck.setEnergy(truck.energyInfo.capacityUnits());
                    truck.controlUnit.setOperator(driver);
                    truck.toggleEngine(true);
                    truck.controlUnit.forward = true;
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    double up = up(truck);
                    double moved = Math.hypot(truck.getX() - parked[0].x, truck.getZ() - parked[0].z);
                    String report = String.format(Locale.ROOT, "truck on its side: up.y %.3f settled, %.3f after 80 ticks of throttle, moved %.2f",
                            settled[0], up, moved);
                    CombinedAssault.LOGGER.info("[tumble] {}", report);
                    driver.discard();
                    release(helper, start, truck);
                    if (settled[0] >= TIPPED_UP || up >= TIPPED_UP || moved > 0.5) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    /** Compound inertia: the turret (outside the hull box) adds to roll about the long axis. */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void aTurretAddsToATanksRollInertia(GameTestHelper helper) {
        BlockPos start = lane(helper, 3);
        AbstractVehicle tank = spawn(helper, TANK, new Vec3(start.getX() + 0.5, start.getY() + 1.2, start.getZ() + 12.5));
        if (tank == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    tank.physicsEngine.prepareInertia();
                    Matrix3f compound = new Matrix3f(tank.physicsEngine.bodyInertia());
                    var hull = tank.getMainCubeOBB();
                    double w = hull.getWidth(), h = hull.getHeight(), d = hull.getDepth();
                    Vec3 com = tank.physicsEngine.center;
                    // Box about its centre + parallel axis to the COM, per unit mass.
                    double boxRoll = (w * w + h * h) / 12 + com.x * com.x + com.y * com.y;
                    int pieces = tank.collisionBody().pieceCount();
                    String report = String.format(Locale.ROOT, "m1a2: %d pieces, roll k^2 %.3f compound vs %.3f hull box, pitch %.3f, yaw %.3f",
                            pieces, compound.m22, boxRoll, compound.m00, compound.m11);
                    CombinedAssault.LOGGER.info("[tumble] {}", report);
                    release(helper, start, tank);
                    if (pieces == 0 || !(compound.m22 > boxRoll * 1.01)) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    static double up(AbstractVehicle vehicle) {
        return vehicle.getMainCubeOBB().obb().getAxes()[1].y;
    }

    private static BlockPos lane(GameTestHelper helper, int index) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X - index * LANE_PITCH, 0, 0);
        forceLane(level, start, true);
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = 0; z <= LANE_LENGTH; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        return start;
    }

    private static void release(GameTestHelper helper, BlockPos start, AbstractVehicle... vehicles) {
        for (AbstractVehicle vehicle : vehicles) {
            vehicle.discard();
        }
        ServerLevel level = helper.getLevel();
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = 0; z <= LANE_LENGTH; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.AIR.defaultBlockState());
            }
        }
        forceLane(level, start, false);
    }

    private static void forceLane(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = start.getZ() >> 4; cz <= (start.getZ() + LANE_LENGTH) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
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
            helper.fail("the test vehicle " + id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
