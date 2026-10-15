package com.wf.combinedassault.autonomy;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Steer to the setpoint heading, bang-bang speed (TowDriver pattern). TODO: no ground path planning. */
final class GroundPilot implements VehiclePilot {

    static final GroundPilot INSTANCE = new GroundPilot();
    private static final float STEER_DEADBAND = 4.0f;
    private static final double STOP = 0.02;

    private GroundPilot() {
    }

    @Override
    public boolean grounded() {
        return true;
    }

    @Override
    public Airframe envelope(AbstractVehicle vehicle) {
        return new Airframe(PhysicsEngine.G, 1.0, Math.toRadians(10.0), Math.toRadians(5.0),
                0.02, 0.01, 0.5, 0.0, 0.0, 0.0, 0.0);
    }

    @Override
    public FlightAttitude attitude(AbstractVehicle vehicle) {
        return FlightAttitude.LEVEL;
    }

    @Override
    public void fly(AbstractVehicle vehicle, Vec3 desired, Vec3 feedForward, float yaw) {
        ControlUnit c = vehicle.controlUnit;
        c.reset();
        double want = Math.sqrt(desired.x * desired.x + desired.z * desired.z);
        if (want < STOP) {
            c.up = true;
            return;
        }
        float heading = (float) Math.toDegrees(Math.atan2(-desired.x, desired.z));
        float error = Mth.wrapDegrees(heading - vehicle.getYRot());
        if (error > STEER_DEADBAND) {
            c.right = true;
        } else if (error < -STEER_DEADBAND) {
            c.left = true;
        }
        c.yRot = heading;
        Vec3 look = vehicle.getLookAngle();
        double speed = vehicle.getDeltaMovement().dot(new Vec3(look.x, 0, look.z).normalize());
        if (speed < want) {
            c.forward = true;
        } else if (speed > want + 0.08) {
            c.backward = true;
        }
    }
}
