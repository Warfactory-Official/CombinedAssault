package com.wf.combinedassault.autonomy;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.multirotor.MultirotorFlight;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Aim + throttle steps. Setpoint slower than {@code minSpeed/4} => orbit (cannot hover); ground roll rotates at
 * 0.9 x {@code minSpeed}. TODO: no runway approach: LANDING = descending orbit to touchdown.
 */
final class FixedWingPilot implements VehiclePilot {

    private static final float ORBIT_RATE = 3.0f;
    private static final float ROTATE_PITCH = -8.0f;
    private static final float MAX_CLIMB_PITCH = -20.0f;
    private static final float MAX_DIVE_PITCH = 15.0f;

    private final double minSpeed;

    FixedWingPilot(double minSpeed) {
        this.minSpeed = minSpeed;
    }

    @Override
    public Airframe envelope(AbstractVehicle vehicle) {
        return new Airframe(PhysicsEngine.G, 2.0, Math.toRadians(25.0), Math.toRadians(2.0),
                0.01, 0.005, 0.3, 0.1, 0.4, 0.4, 0.0);
    }

    @Override
    public FlightAttitude attitude(AbstractVehicle vehicle) {
        return MultirotorFlight.attitudeOf(vehicle.relativeRotDirection(new Vec3(0, 1, 0), false), 1.0);
    }

    @Override
    public void fly(AbstractVehicle vehicle, Vec3 desired, Vec3 feedForward, float yaw) {
        ControlUnit c = vehicle.controlUnit;
        c.reset();
        double horizontal = Math.sqrt(desired.x * desired.x + desired.z * desired.z);
        double speed = vehicle.getDeltaMovement().length();
        boolean ground = vehicle.groundedOrOnDeck();
        double want = Math.max(this.minSpeed, desired.length());
        if (horizontal < this.minSpeed * 0.25) {
            c.yRot = ground ? vehicle.getYRot() : vehicle.getYRot() + ORBIT_RATE;
        } else {
            c.yRot = (float) Math.toDegrees(Math.atan2(-desired.x, desired.z));
        }
        if (ground) {
            c.xRot = speed < this.minSpeed * 0.9 ? 0.0f : ROTATE_PITCH;
        } else {
            c.xRot = Mth.clamp((float) -Math.toDegrees(Math.atan2(desired.y, want)), MAX_CLIMB_PITCH, MAX_DIVE_PITCH);
        }
        if (speed < want) {
            c.forward = true;
        } else if (speed > want * 1.15) {
            c.backward = true;
        }
    }
}
