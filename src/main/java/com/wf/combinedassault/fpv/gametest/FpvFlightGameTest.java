package com.wf.combinedassault.fpv.gametest;

import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllEntities;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.fpv.FpvFlight;

/**
 * Diverges from stock rotary-wing yaw: host clamps to a sideslip limit (15 deg at speed) and weathervanes
 * into the airflow; a drone must hold full yaw authority regardless of airspeed.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class FpvFlightGameTest {

    private static final String TEMPLATE = "empty";

    /** Mirrors the shipped drone's json, so the test measures what's actually flown. */
    private static FpvFlight shipped() {
        JsonObject json = new JsonObject();
        json.addProperty("response", 0.35);
        json.addProperty("yaw_rate", 8.0);
        json.addProperty("pitch_bias", 30.0);
        json.addProperty("bank_per_yaw", 4.0);
        json.addProperty("max_bank", 55.0);
        json.addProperty("max_pitch", 80.0);
        return FpvFlight.of(json);
    }

    private static RotaryWingVehicle airframe(GameTestHelper helper) {
        RotaryWingVehicle vehicle = new RotaryWingVehicle(AllEntities.ROTARY_WING_VEHICLE.get(), helper.getLevel());
        vehicle.setYRot(0);
        vehicle.setXRot(0);
        vehicle.setZRot(0);
        return vehicle;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aimArrivesWithinHalfASecond(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.yRot = 90;
        drone.controlUnit.xRot = 40;

        int ticks = 0;
        while (ticks < 40) {
            flight.tickAttitude(drone, 1.0);
            ticks++;
            if (Math.abs(Mth.wrapDegrees(90 - drone.getYRot())) < 1
                    && Math.abs(Mth.wrapDegrees(40 - drone.getXRot())) < 1) {
                break;
            }
        }
        if (ticks > 12) {
            helper.fail("a ninety degree turn took " + ticks + " ticks; that is not a responsive drone");
            return;
        }
        // ticksToClose self-checks against the measured response; lets a pack author tune by reading it.
        if (flight.ticksToClose(0.99) > 12) {
            helper.fail("ticksToClose disagrees with the measured " + ticks);
            return;
        }
        helper.succeed();
    }

    /** The core divergence: a helicopter at speed won't point off its direction of travel; a drone must. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aimArrivesEvenAtSpeed(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.setDeltaMovement(new Vec3(0, 0, 2.4));
        drone.airSpeed = drone.getDeltaMovement();
        drone.controlUnit.yRot = 90;

        for (int tick = 0; tick < 40; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        double error = Math.abs(Mth.wrapDegrees(90 - drone.getYRot()));
        if (error > 1) {
            helper.fail("at speed the drone stopped " + error + " degrees short of the commanded heading");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void pitchStillFollowsAimWhileAccelerating(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.xRot = 10;
        drone.controlUnit.forward = true;

        for (int tick = 0; tick < 40; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        // expected = aim + pitch_bias: forward throttle reads as pitch, like a real quadcopter.
        double expected = 10 + 30;
        if (Math.abs(drone.getXRot() - expected) > 1) {
            helper.fail("pitch settled at " + drone.getXRot() + ", expected " + expected);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void bankFollowsTheTurnAndLevelsOff(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.rightYaw = true;
        for (int tick = 0; tick < 30; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        if (drone.getZRot() <= 5) {
            helper.fail("a sustained right turn should bank the airframe, roll was " + drone.getZRot());
            return;
        }
        drone.controlUnit.rightYaw = false;
        drone.controlUnit.yRot = drone.getYRot();
        for (int tick = 0; tick < 40; tick++) {
            flight.tickAttitude(drone, 1.0);
        }
        if (Math.abs(drone.getZRot()) > 1) {
            helper.fail("the airframe should level off when the turn stops, roll was " + drone.getZRot());
            return;
        }
        helper.succeed();
    }

    /** Zero rotor power => no yaw authority here; host's stock model keeps control. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void noRotorAuthorityHandsControlBack(GameTestHelper helper) {
        FpvFlight flight = shipped();
        RotaryWingVehicle drone = airframe(helper);
        drone.controlUnit.yRot = 90;
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

    /** {@code FpvFlight.of(null)} must stay null: existing airframes need to be untouched by this feature. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void airframesWithoutTheBlockAreUnchanged(GameTestHelper helper) {
        if (FpvFlight.of(null) != null) {
            helper.fail("a vehicle with no fpv attributes should get no direct control model");
            return;
        }
        helper.succeed();
    }

}
