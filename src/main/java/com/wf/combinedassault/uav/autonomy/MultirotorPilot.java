package com.wf.combinedassault.uav.autonomy;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.wflib.geom.api.flight.Airframe;
import com.wf.wflib.geom.api.flight.FlightAttitude;
import net.minecraft.world.phys.Vec3;

/** Same controller as STABILIZED stick: the setpoint goes straight into {@code Multirotor.step}. */
final class MultirotorPilot implements VehiclePilot {

    static final MultirotorPilot INSTANCE = new MultirotorPilot();

    private MultirotorPilot() {
    }

    @Override
    public Airframe envelope(AbstractVehicle vehicle) {
        return ((RotaryWingVehicle) vehicle).multirotor.airframe(vehicle, 1.0, false);
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
