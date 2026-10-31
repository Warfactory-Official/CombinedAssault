package com.wf.combinedassault.weapon.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;

/** Emplacements stand on their legs: shoved sideways, they slide or rock back, never tip. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class EmplacementStanceGameTest {

    private static final float TILT = 5;

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aShovedFlak36StaysOnItsOutriggers(GameTestHelper helper) {
        stance(helper, -3200, CombinedAssault.modLocation("flak36"));
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aShovedSpg9StaysOnItsTripod(GameTestHelper helper) {
        stance(helper, -3400, CombinedAssault.modLocation("spg9"));
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aShovedLaserEmplacementStaysOnItsSkirt(GameTestHelper helper) {
        stance(helper, -3600, CombinedAssault.modLocation("laser_emplacement"));
    }

    private static void stance(GameTestHelper helper, int lane, ResourceLocation id) {
        TestLanes.run(helper, lane, 8, 8, id, (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    String rest = id + " pitch " + v.getXRot() + " roll " + v.getZRot();
                    helper.assertTrue(level(v), "settled tilted: " + rest);
                    v.setDeltaMovement(new Vec3(0.3, 0, 0.3));
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    helper.assertTrue(level(v), "tipped after a shove: " + id + " pitch " + v.getXRot() + " roll " + v.getZRot());
                }), at -> {});
    }

    private static boolean level(AbstractVehicle v) {
        return Math.abs(Mth.wrapDegrees(v.getXRot())) < TILT && Math.abs(Mth.wrapDegrees(v.getZRot())) < TILT;
    }
}
