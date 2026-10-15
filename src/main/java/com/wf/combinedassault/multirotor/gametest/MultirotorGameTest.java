package com.wf.combinedassault.multirotor.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.fpv.gametest.KamikazeKit;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class MultirotorGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation QUAD = CombinedAssault.modLocation("mavic");

    @Nullable
    private static RotaryWingVehicle spawn(GameTestHelper helper, ResourceLocation id, int x, int height) {
        return spawnAt(helper, id, helper.absolutePos(new BlockPos(x, height, 1)));
    }

    @Nullable
    private static RotaryWingVehicle spawnAt(GameTestHelper helper, ResourceLocation id, BlockPos at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " not in the vehicle roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        helper.getLevel().addFreshEntity(vehicle);
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        vehicle.toggleEngine(true);
        vehicle.setPower(100);
        RotaryWingVehicle rotor = (RotaryWingVehicle) vehicle;
        if (rotor.multirotor == null) {
            helper.fail(id + " has no multirotor block");
            return null;
        }
        return rotor;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void stabilizedHoverHolds(GameTestHelper helper) {
        RotaryWingVehicle quad = spawn(helper, QUAD, 1, 30);
        if (quad == null) {
            return;
        }
        quad.multirotor.setFpv(false);
        Vec3 start = quad.position();
        helper.runAfterDelay(80, () -> {
            Vec3 d = quad.position().subtract(start);
            helper.assertTrue(Math.abs(d.y) < 0.5, "hover drifted vertically " + d.y);
            helper.assertTrue(d.horizontalDistance() < 0.5, "hover drifted horizontally " + d.horizontalDistance());
            quad.discard();
            helper.succeed();
        });
    }

    /** Own lane at z -2400: the run covers ~80 blocks of +Z, past any arena's ticking chunks. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void stickFliesCommandedSpeedNoseDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(new BlockPos(1, 40, 1)).offset(0, 0, -2400);
        int cx = lane.getX() >> 4;
        int cz0 = lane.getZ() >> 4;
        for (int cz = cz0 - 1; cz <= cz0 + 8; cz++) {
            ForcedChunks.set(level, cx, cz, true);
        }
        RotaryWingVehicle[] quad = new RotaryWingVehicle[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(lane), "lane not ticking"))
                .thenExecute(() -> {
                    quad[0] = spawnAt(helper, QUAD, lane);
                    quad[0].multirotor.setFpv(false);
                    quad[0].controlUnit.forward = true;
                    quad[0].controlUnit.yRotKeep = true;
                })
                .thenIdle(110)
                .thenExecute(() -> {
                    RotaryWingVehicle q = quad[0];
                    double speed = q.getDeltaMovement().horizontalDistance();
                    helper.assertTrue(Math.abs(speed - 0.75) < 0.05, "cruise " + speed + " b/t, stick asks 0.75");
                    helper.assertTrue(q.getXRot() > 5, "forward flight should be nose down, pitch " + q.getXRot());
                    helper.assertTrue(q.getDeltaMovement().z > 0.7, "yaw 0 forward is +Z, v " + q.getDeltaMovement());
                    q.discard();
                    for (int cz = cz0 - 1; cz <= cz0 + 8; cz++) {
                        ForcedChunks.set(level, cx, cz, false);
                    }
                })
                .thenSucceed();
    }

    /** Same airframe, full throttle, level: the strapped PG-7V (2.6 kg) must cost climb. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void payloadMassSlowsClimb(GameTestHelper helper) {
        RotaryWingVehicle loaded = spawn(helper, QUAD, 1, 30);
        RotaryWingVehicle empty = spawn(helper, QUAD, 8, 30);
        if (loaded == null || empty == null) {
            return;
        }
        KamikazeKit.strap(loaded);
        double[] y0 = new double[2];
        helper.runAfterDelay(2, () -> {
            for (RotaryWingVehicle v : new RotaryWingVehicle[]{loaded, empty}) {
                v.multirotor.setFpv(true);
                v.setCollectivePitch(100);
                v.controlUnit.xRot = 0;
                v.controlUnit.yRotKeep = true;
            }
            y0[0] = loaded.getY();
            y0[1] = empty.getY();
        });
        helper.runAfterDelay(12, () -> {
            double climbLoaded = loaded.getY() - y0[0];
            double climbEmpty = empty.getY() - y0[1];
            helper.assertTrue(loaded.physicsEngine.mass - empty.physicsEngine.mass > 2.5,
                    "masses " + loaded.physicsEngine.mass + " / " + empty.physicsEngine.mass);
            helper.assertTrue(climbEmpty > 1.5 * climbLoaded,
                    "10 ticks full throttle: empty climbed " + climbEmpty + ", loaded " + climbLoaded);
            loaded.discard();
            empty.discard();
            helper.succeed();
        });
    }
}
