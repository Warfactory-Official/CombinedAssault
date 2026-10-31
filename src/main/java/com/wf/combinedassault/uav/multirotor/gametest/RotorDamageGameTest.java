package com.wf.combinedassault.uav.multirotor.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Mavic rotor units FL / FR / RL / RR: own pools, own HUD cells; weak => thrust capped, dead => tumble. */
@PrefixGameTestTemplate(false)
public class RotorDamageGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation QUAD = CombinedAssault.modLocation("mavic");
    private static final List<String> UNITS = List.of("rotor FL", "rotor FR", "rotor RL", "rotor RR");

    private static Vec3 rotor(RotaryWingVehicle quad, String id) {
        VehicleHits hits = quad.hits();
        return hits.centre(i -> id.equals(hits.model().nodes().get(i).systemId()));
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void eachRotorIsItsOwnSystem(GameTestHelper helper) {
        RotaryWingVehicle quad = MultirotorGameTest.spawn(helper, QUAD, 1, 30);
        if (quad == null) {
            return;
        }
        helper.runAfterDelay(2, () -> {
            HullComponents systems = quad.hullComponents();
            List<String> labels = VehicleSystems.cells(quad).stream().map(VehicleSystems.Cell::label).toList();
            int fr = systems.indexOf("rotor FR");
            boolean all = UNITS.stream().allMatch(id -> systems.indexOf(id) >= 0);
            systems.setHealth(fr, systems.maxHealth(fr) * 0.5f);
            float others = Math.min(systems.health(systems.indexOf("rotor FL")) / systems.maxHealth(systems.indexOf("rotor FL")),
                    systems.health(systems.indexOf("rotor RR")) / systems.maxHealth(systems.indexOf("rotor RR")));
            quad.discard();
            helper.assertTrue(all && systems.indexOf("rotor") < 0, "rotor pools " + labels);
            helper.assertTrue(labels.containsAll(List.of("ROTR FL", "ROTR FR", "ROTR RL", "ROTR RR")),
                    "HUD cells " + labels);
            helper.assertTrue(others == 1, "FR hit spilled onto others " + others);
            helper.succeed();
        });
    }

    /** One rotor down a band: share = that rotor's output (others throttled to balance), hover held. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aWeakRotorCapsTheBalancedThrust(GameTestHelper helper) {
        RotaryWingVehicle quad = MultirotorGameTest.spawn(helper, QUAD, 1, 30);
        if (quad == null) {
            return;
        }
        quad.multirotor.setFpv(false);
        helper.runAfterDelay(2, () -> {
            HullComponents systems = quad.hullComponents();
            int fr = systems.indexOf("rotor FR");
            systems.setHealth(fr, systems.maxHealth(fr) * 0.45f);
            double share = quad.rotorShare();
            float fr0 = systems.effectiveness(fr);
            Vec3 start = quad.position();
            helper.runAfterDelay(80, () -> {
                double drop = start.y - quad.position().y;
                quad.discard();
                helper.assertTrue(Math.abs(share - fr0) < 1e-4 && share > 0 && share < 1,
                        "share " + share + ", FR " + fr0);
                helper.assertTrue(Math.abs(drop) < 0.5, "hover lost " + drop);
                helper.succeed();
            });
        });
    }

    /** FR dead: no balanced thrust; FR side drops (tumble toward it), then the drone falls. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aDeadRotorTumblesTowardItAndFalls(GameTestHelper helper) {
        RotaryWingVehicle quad = MultirotorGameTest.spawn(helper, QUAD, 1, 40);
        if (quad == null) {
            return;
        }
        quad.multirotor.setFpv(false);
        helper.runAfterDelay(20, () -> {
            HullComponents systems = quad.hullComponents();
            systems.setHealth(systems.indexOf("rotor FR"), 0);
            double y0 = quad.getY();
            helper.runAfterDelay(6, () -> {
                double fr = rotor(quad, "rotor FR").y;
                double rl = rotor(quad, "rotor RL").y;
                double share = quad.rotorShare();
                helper.runAfterDelay(30, () -> {
                    double drop = y0 - quad.getY();
                    quad.discard();
                    helper.assertTrue(share == 0, "balanced share " + share);
                    helper.assertTrue(fr < rl - 0.05, "FR " + fr + " not below RL " + rl);
                    helper.assertTrue(drop > 2, "fell " + drop);
                    helper.succeed();
                });
            });
        });
    }
}
