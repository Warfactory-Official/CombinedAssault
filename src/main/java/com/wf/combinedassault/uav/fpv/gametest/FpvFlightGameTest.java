package com.wf.combinedassault.uav.fpv.gametest;

import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.uav.fpv.FpvFlight;
import com.wf.combinedassault.aircraft.AircraftContent;

@PrefixGameTestTemplate(false)
public class FpvFlightGameTest {

    private static final String TEMPLATE = "empty";

    /** Mirrors the shipped drone's json, so the test measures what's actually flown. */
    private static FpvFlight shipped() {
        JsonObject json = new JsonObject();
        json.addProperty("pitch_rate", 12.0);
        json.addProperty("roll_rate", 15.0);
        json.addProperty("yaw_rate", 8.0);
        json.addProperty("camera_tilt", 20.0);
        return FpvFlight.of(json);
    }

    private static RotaryWingVehicle airframe(GameTestHelper helper) {
        RotaryWingVehicle vehicle = new RotaryWingVehicle(AircraftContent.ROTARY_WING_VEHICLE.get(), helper.getLevel());
        vehicle.setYRot(0);
        vehicle.setXRot(0);
        vehicle.setZRot(0);
        return vehicle;
    }

    private static Vector3f up(RotaryWingVehicle drone) {
        return drone.rotYXZ().transform(new Vector3f(0, 1, 0));
    }

    /** Held roll key: full barrel roll, upside down halfway, level again after 360 deg. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void rollKeyFlipsThroughInverted(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.right = true;
        for (int tick = 0; tick < 12; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        if (up(drone).y > -0.99f) {
            helper.fail("12 ticks x 15 deg should be inverted, body up " + up(drone));
            return;
        }
        for (int tick = 0; tick < 12; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        if (up(drone).y < 0.99f || Math.abs(Mth.wrapDegrees(drone.getYRot())) > 1) {
            helper.fail("full roll should end level on the same heading, up " + up(drone) + " yaw " + drone.getYRot());
            return;
        }
        helper.succeed();
    }

    /** Mouse pitch is a body-frame turn with no limit: a loop passes straight up, inverted, straight down. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void mousePitchLoops(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.turnX = -30;
        Vector3f[] ups = new Vector3f[12];
        for (int tick = 0; tick < 12; tick++) {
            flight.tickAttitude(drone, 1.0);
            ups[tick] = up(drone);
        }
        if (ups[5].y > -0.99f || ups[11].y < 0.99f) {
            helper.fail("loop: up after 180 deg " + ups[5] + ", after 360 deg " + ups[11]);
            return;
        }
        helper.succeed();
    }

    /** Acro: no self-levelling. Sticks released => attitude held, even banked. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void releasedSticksHoldAttitude(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.turnY = 20;
        drone.controlUnit.left = true;
        for (int tick = 0; tick < 3; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        drone.controlUnit.reset();
        Vector3f held = up(drone);
        float yaw = drone.getYRot();
        for (int tick = 0; tick < 40; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        if (up(drone).distance(held) > 1e-4f || drone.getYRot() != yaw) {
            helper.fail("attitude drifted with sticks released: " + held + " -> " + up(drone));
            return;
        }
        helper.succeed();
    }

    /** Zero rotor power => no yaw authority here; host's stock model keeps control. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void noRotorAuthorityHandsControlBack(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.turnY = 90;
        if (flight.tickAttitude(drone, 0)) {
            helper.fail("an unpowered drone claimed attitude control");
            return;
        }
        if (drone.getYRot() != 0) {
            helper.fail("an unpowered drone turned anyway, to " + drone.getYRot());
            return;
        }
        helper.succeed();
    }

    /** Mouse heading turns the tilt with it (course changes); mouse right = view right, upright and inverted. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void mouseHeadingTurnsTheThrust(GameTestHelper helper) {
        FpvFlight flight = shipped();
        for (float roll : new float[]{0, 180}) {
            RotaryWingVehicle drone = airframe(helper);
            drone.setXRot(30);
            drone.setZRot(roll);
            Vector3f before = up(drone);
            Vector3f rightBefore = drone.rotYXZ().transform(new Vector3f(-1, 0, 0));
            drone.controlUnit.turnY = 30;
            flight.tickAttitude(drone, 1.0);
            Vector3f expected = new Vector3f(before).rotateY((float) Math.toRadians(roll == 0 ? -30 : 30));
            if (up(drone).distance(expected) > 1e-3f) {
                helper.fail("roll " + roll + ": thrust " + before + " -> " + up(drone) + ", expected " + expected);
                return;
            }
            if (drone.rotYXZ().transform(new Vector3f(0, 0, 1)).dot(rightBefore) <= 0) {
                helper.fail("roll " + roll + ": mouse right swung the view left");
                return;
            }
        }
        helper.succeed();
    }

    /** Server adopts every report, however far; no new input => held, not re-stepped. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void reportedAttitudeAdoptedAndHeld(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        ControlUnit c = drone.controlUnit;
        c.reported = true;
        c.reportedXRot = 120;
        c.reportedYRot = 45;
        c.reportedZRot = -60;
        c.right = true;
        for (int tick = 0; tick < 3; tick++) {
            flight.tickAttitude(drone, 1.0);
            if (drone.getXRot() != 120 || drone.getYRot() != 45 || drone.getZRot() != -60) {
                helper.fail("tick " + tick + ": " + drone.getXRot() + "/" + drone.getYRot() + "/" + drone.getZRot());
                return;
            }
        }
        helper.succeed();
    }

    /** {@code FpvFlight.of(null)} must stay null: existing airframes need to be untouched by this feature. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void airframesWithoutTheBlockAreUnchanged(GameTestHelper helper) {
        if (FpvFlight.of(null) != null) {
            helper.fail("a vehicle with no fpv attributes should get no direct control model");
            return;
        }
        helper.succeed();
    }

}
