package com.wf.combinedassault.autonomy;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.world.phys.Vec3;

/** Same controller as STABILIZED stick: the setpoint goes straight into {@code Multirotor.step}. */
final class MultirotorPilot implements VehiclePilot {

    static final MultirotorPilot INSTANCE = new MultirotorPilot();

    private MultirotorPilot() {
    }

    @Override
    public Airframe envelope(AbstractVehicle vehicle) {
        return ((RotaryWingVehicle) vehicle).multirotor.airframe(vehicle.curbWeight, 1.0);
    }

    @Override
    public FlightAttitude attitude(AbstractVehicle vehicle) {
        return ((RotaryWingVehicle) vehicle).multirotor.attitude();
    }

    @Override
    public void fly(AbstractVehicle vehicle, Vec3 desired, Vec3 feedForward, float yaw) {
        ((RotaryWingVehicle) vehicle).multirotor.command(desired, feedForward, yaw);
    }
}
