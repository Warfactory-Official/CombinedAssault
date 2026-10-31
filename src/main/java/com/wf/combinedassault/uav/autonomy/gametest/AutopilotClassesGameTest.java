package com.wf.combinedassault.uav.autonomy.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.uav.autonomy.VehicleDrone;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.combinedassault.vehicle.pojo.AutonomyInfo;
import com.wf.wflib.drone.api.DroneTask;
import com.wf.wflib.drone.api.DroneTasking;
import com.wf.wflib.drone.api.DroneTracker;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Each pilot completes one MOVE_TO; body attached in-test (shipped packs give these no autonomy block). */
@PrefixGameTestTemplate(false)
public class AutopilotClassesGameTest {

    private static final String TEMPLATE = "empty";

    private static void run(GameTestHelper helper, String id, int laneZ, int route, double minSpeed) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 0, laneZ);
        Vec3 target = Vec3.atBottomCenterOf(origin.offset(route, 0, 0));
        int cz = origin.getZ() >> 4;
        int cx0 = (origin.getX() >> 4) - 1;
        int cx1 = ((origin.getX() + route) >> 4) + 2;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                ForcedChunks.set(level, cx, z, true);
            }
        }
        AbstractVehicle[] v = new AbstractVehicle[1];
        boolean[] reached = new boolean[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(origin), "lane not ticking"))
                .thenExecute(() -> {
                    v[0] = TestLanes.spawn(helper, CombinedAssault.modLocation(id), Vec3.atBottomCenterOf(origin));
                    v[0].setYRot(-90);
                    v[0].setEnergy(v[0].energyInfo.capacityUnits());
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    AutonomyInfo info = new AutonomyInfo();
                    info.tasks = List.of("moveto", "exfil");
                    info.cruiseAltitude = 20.0;
                    info.minSpeed = minSpeed;
                    v[0].drone = new VehicleDrone(v[0], info);
                    DroneTracker.track(v[0].drone);
                    String error = DroneTasking.assign(v[0].drone, null, DroneTask.Kind.MOVE_TO, target);
                    helper.assertTrue(error == null, "assign: " + error);
                })
                .thenWaitUntil(() -> {
                    if (v[0].position().subtract(target).horizontalDistance() < 21) {
                        reached[0] = true;
                    }
                    helper.assertTrue(reached[0], id + ": " + v[0].drone.flight().getDroneState() + " at "
                            + v[0].position().subtract(Vec3.atBottomCenterOf(origin)) + " v " + v[0].getDeltaMovement());
                })
                .thenExecute(() -> {
                    DroneTracker.untrack(v[0].drone);
                    v[0].discard();
                    for (int cx = cx0; cx <= cx1; cx++) {
                        for (int z = cz - 1; z <= cz + 1; z++) {
                            ForcedChunks.set(level, cx, z, false);
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 2000)
    public static void helicopterFliesAMoveTo(GameTestHelper helper) {
        run(helper, "mh60l", -3200, 64, 0.8);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 3000)
    public static void fixedWingFliesAMoveTo(GameTestHelper helper) {
        run(helper, "mq9", -3400, 400, 1.2);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 2000)
    public static void wheeledDrivesAMoveTo(GameTestHelper helper) {
        run(helper, "humvee", -3600, 64, 0.8);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 2000)
    public static void trackedDrivesAMoveTo(GameTestHelper helper) {
        run(helper, "t90", -3800, 64, 0.8);
    }
}
