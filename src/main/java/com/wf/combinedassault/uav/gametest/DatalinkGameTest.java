package com.wf.combinedassault.uav.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.uav.UavCamera;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class DatalinkGameTest {
    private static final String TEMPLATE = "empty";

    /** Operator's body past the datalink range: inputs dropped, the hovering Mavic holds where it was. */
    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void lostLinkDropsInputsAndHolds(GameTestHelper helper) {
        AbstractVehicle quad = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"),
                helper.absoluteVec(new Vec3(1.5, 30, 1.5)));
        quad.setEnergy(quad.energyInfo.capacityUnits());
        quad.toggleEngine(true);
        quad.setPower(100);
        ArmorStand pilot = new ArmorStand(helper.getLevel(), quad.getX(), quad.getY(), quad.getZ());
        double[] heldY = new double[1];
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.getLevel().addFreshEntity(pilot);
                    helper.assertTrue(pilot.startRiding(quad, true), "pilot could not board");
                    quad.setDetachedBodyAnchor(pilot, quad.position());
                })
                .thenIdle(30)
                .thenExecute(() -> helper.assertTrue(quad.link() > 0.99f && !quad.linkLost(),
                        "link beside the body " + quad.link()))
                .thenExecute(() -> {
                    quad.setDetachedBodyAnchor(pilot, quad.position().add(UavCamera.RANGE + 100, 0, 0));
                    heldY[0] = quad.getY();
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertTrue(quad.linkLost(), "link past range " + quad.link());
                    quad.controlUnit.up = true;
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertFalse(quad.controlUnit.up, "climb input survived a lost link");
                    helper.assertTrue(quad.autopilotControl, "no failsafe hold");
                    helper.assertTrue(Math.abs(quad.getY() - heldY[0]) < 1.5,
                            "drifted " + (quad.getY() - heldY[0]) + " in hold");
                    pilot.stopRiding();
                    pilot.discard();
                    quad.discard();
                })
                .thenSucceed();
    }
}
