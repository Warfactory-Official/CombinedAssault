package com.wf.combinedassault.uav.autonomy;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.uav.multirotor.MultirotorFlight;
import com.wf.combinedassault.dynamics.PhysicsEngine;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.combinedassault.aircraft.AircraftAerodynamics;
import com.wf.wflib.geom.api.flight.Airframe;
import com.wf.wflib.geom.api.flight.FlightAttitude;
import net.minecraft.world.phys.Vec3;

/** Hover loop with a velocity target instead of an anchor; stick keys cleared (rotary never resets them). */
final class HelicopterPilot implements VehiclePilot {

    static final HelicopterPilot INSTANCE = new HelicopterPilot();

    private HelicopterPilot() {
    }

    @Override
    public Airframe envelope(AbstractVehicle vehicle) {
        AircraftAerodynamics a = ((RotaryWingVehicle) vehicle).aerodynamics();
        return new Airframe(PhysicsEngine.G, 2.0, Math.toRadians(a.hoverMaxTilt), Math.toRadians(3.0),
                0.02, 0.01, 0.5, 0.2, a.hoverClimbRate, a.hoverClimbRate, 0.0);
    }

    @Override
    public FlightAttitude attitude(AbstractVehicle vehicle) {
        return MultirotorFlight.attitudeOf(vehicle.relativeRotDirection(new Vec3(0, 1, 0), false),
                ((RotaryWingVehicle) vehicle).getCollectivePitch() / 100.0);
    }

    @Override
    public void fly(AbstractVehicle vehicle, Vec3 desired, Vec3 feedForward, float yaw) {
        RotaryWingVehicle rotor = (RotaryWingVehicle) vehicle;
        ControlUnit c = rotor.controlUnit;
        c.reset();
        c.yRot = yaw;
        rotor.hoverMode = true;
        rotor.autopilotVelocity = desired;
    }
}
