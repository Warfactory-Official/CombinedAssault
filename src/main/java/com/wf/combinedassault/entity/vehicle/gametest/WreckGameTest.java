package com.wf.combinedassault.entity.vehicle.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;

/** Aircraft killed in the air: wreck turns out of its kill attitude on the ground, comes to rest. Lane forces +-16 blocks. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class WreckGameTest {

    private static final String TEMPLATE = "empty";
    private static final int DROP = 18;
    private static final int REST = 100;

    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void aJetKilledInADiveDoesNotStayWedgedNoseDown(GameTestHelper helper) {
        wreck(helper, -2800, CombinedAssault.modLocation("a10"), 30, 0, new Vec3(0, -0.4, 0.3),
                v -> Math.abs(Mth.wrapDegrees(v.getXRot())) < 15,
                v -> "pitch " + v.getXRot());
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void aHelicopterKilledBankedDoesNotLandFrozenAtItsBank(GameTestHelper helper) {
        wreck(helper, -3000, CombinedAssault.modLocation("ka52"), 0, 45, Vec3.ZERO,
                v -> Math.abs(Mth.wrapDegrees(v.getZRot()) - 45) > 10,
                v -> "roll " + v.getZRot());
    }

    private static void wreck(GameTestHelper helper, int lane, ResourceLocation id, float pitch, float roll, Vec3 velocity,
                              java.util.function.Predicate<AbstractVehicle> turned,
                              java.util.function.Function<AbstractVehicle, String> attitude) {
        double[] floorY = new double[1];
        TestLanes.run(helper, lane, 24, 48, id, (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    floorY[0] = v.getY();
                    v.setPos(v.getX(), v.getY() + DROP, v.getZ() - 6);
                    v.setXRot(pitch);
                    v.setZRot(roll);
                    v.setDeltaMovement(velocity);
                    v.setDestroyed();
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicle.get().getY() < floorY[0] + 3, id + " never landed"))
                .thenIdle(REST)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    double speed = v.getDeltaMovement().length();
                    String state = id + " " + attitude.apply(v) + ", speed " + speed + ", y " + (v.getY() - floorY[0]);
                    helper.assertTrue(turned.test(v), "wreck held its kill attitude: " + state);
                    helper.assertTrue(speed < 0.1, "wreck still moving: " + state);
                    helper.assertTrue(v.getY() > floorY[0] - 1, "wreck sank: " + state);
                }), at -> {});
    }
}
