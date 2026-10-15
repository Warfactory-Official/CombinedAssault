package com.wf.combinedassault.autonomy.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.wflib.block.ModBlocks;
import com.wf.wflib.block.entity.DronePadBlockEntity;
import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.DroneTasking;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Lane z -3000 from the arena, route +X 48 blocks: chunks forced along it. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class AutopilotGameTest {

    private static final String TEMPLATE = "empty";
    private static final int ROUTE = 48;

    private static void force(ServerLevel level, BlockPos origin, boolean on) {
        for (int cx = (origin.getX() >> 4) - 1; cx <= ((origin.getX() + ROUTE) >> 4) + 1; cx++) {
            for (int cz = (origin.getZ() >> 4) - 1; cz <= (origin.getZ() >> 4) + 1; cz++) {
                ForcedChunks.set(level, cx, cz, on);
            }
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 1600)
    public static void moveToSurvivesATakeover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 0, -3000);
        Vec3 target = Vec3.atBottomCenterOf(origin.offset(ROUTE, 0, 0));
        force(level, origin, true);
        AbstractVehicle[] quad = new AbstractVehicle[1];
        ArmorStand[] pilot = new ArmorStand[1];
        double[] heldFrom = new double[1];
        boolean[] reached = new boolean[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(origin), "lane not ticking"))
                .thenExecute(() -> {
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                        }
                    }
                    quad[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"),
                            Vec3.atBottomCenterOf(origin));
                    quad[0].setEnergy(quad[0].energyInfo.capacityUnits());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(quad[0].drone != null, "quadcopter has no autonomy body");
                    helper.assertTrue(DroneTracker.byId(level, quad[0].getUUID()) == quad[0].drone, "body not tracked");
                    String error = DroneTasking.assign(quad[0].drone, null, DroneTask.Kind.MOVE_TO, target);
                    helper.assertTrue(error == null, "assign: " + error);
                })
                .thenWaitUntil(() -> helper.assertTrue(quad[0].getX() > origin.getX() + 8,
                        "outbound: " + quad[0].drone.flight().getDroneState() + " at " + quad[0].position()))
                .thenExecute(() -> {
                    pilot[0] = new ArmorStand(level, quad[0].getX(), quad[0].getY(), quad[0].getZ());
                    level.addFreshEntity(pilot[0]);
                    helper.assertTrue(pilot[0].startRiding(quad[0], true), "pilot could not board");
                    heldFrom[0] = quad[0].getX();
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    double drift = quad[0].getX() - heldFrom[0];
                    // Neutral stick brakes from cruise: stopping distance only, autopilot would add ~36.
                    helper.assertTrue(drift < 12, "autopilot kept flying under a pilot: +" + drift);
                    pilot[0].stopRiding();
                    pilot[0].discard();
                })
                .thenWaitUntil(() -> {
                    // MOVE_TO completes inside LEG_ARRIVAL_RADIUS (20).
                    if (quad[0].position().subtract(target).horizontalDistance() < 21) {
                        reached[0] = true;
                    }
                    helper.assertTrue(reached[0], "resume: " + quad[0].drone.flight().getDroneState()
                            + " at " + quad[0].position());
                })
                .thenWaitUntil(() -> helper.assertTrue(quad[0].drone.flight().getDroneState() == DroneState.IDLE,
                        "homeward: " + quad[0].drone.flight().getDroneState()))
                .thenExecute(() -> {
                    double home = quad[0].position().subtract(Vec3.atBottomCenterOf(origin)).horizontalDistance();
                    helper.assertTrue(home < 12, "landed " + home + " from home");
                    helper.assertFalse(quad[0].isEngineOn(), "engine still running after landing");
                })
                .thenExecute(() -> {
                    quad[0].discard();
                    force(level, origin, false);
                })
                .thenSucceed();
    }

    /** A UAV parked on a pad flies the pad's dispatch; no wflib fallback drone is spawned. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aPadDispatchesTheParkedUav(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos padPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(padPos, ModBlocks.DRONE_PAD.get().defaultBlockState());
        AbstractVehicle quad = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"),
                Vec3.atBottomCenterOf(padPos.above()).add(3, 0, 0));
        quad.setEnergy(quad.energyInfo.capacityUnits());
        helper.runAfterDelay(3, () -> {
            DronePadBlockEntity pad = (DronePadBlockEntity) level.getBlockEntity(padPos);
            ServerPlayer player = FakePlayerFactory.getMinecraft(level);
            int before = DroneTracker.drones(level).size();
            DroneMission.Result result = pad.dispatch(player, DroneTask.Kind.LOITER, quad.position().add(10, 0, 0));
            helper.assertTrue(result.ok(), "dispatch: " + result.error());
            helper.assertTrue(result.drones().size() == 1 && result.drones().get(0) == quad.drone,
                    "pad flew " + result.drones() + ", not the parked quadcopter");
            helper.assertTrue(DroneTracker.drones(level).size() == before, "pad spawned a wflib drone too");
            helper.assertTrue(player.getUUID().equals(quad.drone.getOperatorId()), "operator not stamped");
            quad.drone.orders().setProgram(com.wf.wflib.drone.DroneProgram.EMPTY);
            quad.discard();
            level.removeBlock(padPos, false);
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void unsupportedTaskIsRefused(GameTestHelper helper) {
        AbstractVehicle quad = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"),
                helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
        helper.runAfterDelay(2, () -> {
            String error = DroneTasking.assign(quad.drone, null, DroneTask.Kind.DELIVER, quad.position());
            helper.assertTrue(error != null, "a quadcopter accepted a delivery");
            helper.assertTrue(quad.drone.flight().getDroneState() == DroneState.IDLE, "refusal changed state");
            quad.discard();
            helper.succeed();
        });
    }
}
