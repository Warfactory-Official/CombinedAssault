package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.wflib.util.OBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Settled pose per ground vehicle, one {@code [geom]} row: hull box vs floor, authored COM, wheel track.
 * Input for physical {@code physics_info.center}. {@code -Pbench=geom}.
 */
@GameTestHolder(ClimbBench.NAMESPACE)
public class GeometryBench {

    private static final String[] VEHICLES = {"humvee", "m1129", "centauro", "m983", "mim104", "m1a2", "t84", "t90", "merkava"};
    private static final int LANE_X = 3000;
    private static final int LANE_PITCH = 40;
    private static final int HALF = 12;

    @GameTestGenerator
    public static List<TestFunction> geometryBench() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            String name = "geom_" + vehicle;
            if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                continue;
            }
            int at = lane++;
            tests.add(new TestFunction("geombench", name, ClimbBench.NAMESPACE + ":empty", 200, 0, false,
                    helper -> run(helper, vehicle, at)));
        }
        for (String vehicle : new String[]{"humvee", "m1a2"}) {
            String name = "spin_" + vehicle;
            if (!name.contains(System.getProperty("combinedassault.bench.filter", ""))) {
                continue;
            }
            int at = lane++;
            tests.add(new TestFunction("geombench", name, ClimbBench.NAMESPACE + ":empty", 260, 0, false,
                    helper -> spin(helper, vehicle, at)));
        }
        return tests;
    }

    /** Parked hull, yaw and spin every 10 ticks: a still vehicle must not turn. */
    private static void spin(GameTestHelper helper, String name, int lane) {
        ServerLevel level = helper.getLevel();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + lane * LANE_PITCH, 0, 0);
        floor(level, start, true);
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.2, start.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        var sequence = helper.startSequence();
        for (int i = 0; i < 24; i++) {
            sequence.thenIdle(10).thenExecute(() -> CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                    "[spin] %s t%d yRot %.2f w %s v %s ground %b",
                    name, vehicle.tickCount, vehicle.getYRot(), vehicle.physicsEngine.angularVelocity,
                    vehicle.getDeltaMovement(), vehicle.onGround())));
        }
        sequence.thenExecute(() -> {
            vehicle.discard();
            floor(level, start, false);
        }).thenSucceed();
    }

    private static void run(GameTestHelper helper, String name, int lane) {
        ServerLevel level = helper.getLevel();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, name);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            CombinedAssault.LOGGER.info("[geom] {} absent", name);
            helper.succeed();
            return;
        }
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + lane * LANE_PITCH, 0, 0);
        floor(level, start, true);
        AbstractVehicle vehicle = data.get().construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        level.addFreshEntity(vehicle);
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    double floorY = start.getY() + 1;
                    OBB hull = vehicle.getMainCubeOBB().obb();
                    Vector3f e = hull.extents();
                    Vec3 center = vehicle.physicsEngine.center;
                    double[] com = new double[3];
                    hull.localToWorld(new Vector3f((float) center.x, (float) center.y, (float) center.z), hull.getAxes(), com);
                    Quaternionf inverseYaw = new Quaternionf().rotateY((float) Math.toRadians(vehicle.getYRot()));
                    double track = 0, wheelbase = 0;
                    int grounded = 0;
                    for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
                        if (unit.isGrounded()) {
                            grounded++;
                        }
                        Vector3f arm = inverseYaw.transform(unit.worldPivotPosition().subtract(hull.centerX(), hull.centerY(), hull.centerZ()).toVector3f());
                        track = Math.max(track, Math.abs(arm.x));
                        wheelbase = Math.max(wheelbase, Math.abs(arm.z));
                    }
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[geom] %s ticks %d hullCentreAboveFloor %.3f ext %.3f %.3f %.3f authoredCentre %.2f %.2f %.2f comAboveFloor %.3f "
                                    + "halfTrack %.3f halfBase %.3f grounded %d/%d mass %.0f",
                            name, vehicle.tickCount, hull.centerY() - floorY, e.x, e.y, e.z, center.x, center.y, center.z, com[1] - floorY,
                            track, wheelbase, grounded, vehicle.getSuspensionUnits().size(), vehicle.physicsEngine.mass));
                    vehicle.discard();
                    floor(level, start, false);
                })
                .thenSucceed();
    }

    private static void floor(ServerLevel level, BlockPos start, boolean place) {
        for (int cx = (start.getX() - HALF) >> 4; cx <= (start.getX() + HALF) >> 4; cx++) {
            for (int cz = (start.getZ() - HALF) >> 4; cz <= (start.getZ() + HALF) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, place);
            }
        }
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }
}
