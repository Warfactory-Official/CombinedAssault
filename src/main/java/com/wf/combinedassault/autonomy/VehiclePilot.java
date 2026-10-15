package com.wf.combinedassault.autonomy;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.entity.vehicle.TrackedVehicle;
import com.wf.combinedassault.entity.vehicle.WheeledVehicle;
import com.wf.combinedassault.vehicle.pojo.AutonomyInfo;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.world.phys.Vec3;

/** Turns a wflib guidance setpoint into this vehicle class's controls, server side, no driver aboard. */
public interface VehiclePilot {

    /** Limits the planner (stopping distance, approach speed, range) works to. */
    Airframe envelope(AbstractVehicle vehicle);

    FlightAttitude attitude(AbstractVehicle vehicle);

    /**
     * @param desired blocks/tick
     * @param feedForward blocks/tick²
     * @param yaw MC degrees
     */
    void fly(AbstractVehicle vehicle, Vec3 desired, Vec3 feedForward, float yaw);

    /** Never leaves the ground: cruise altitude 0, so TAKEOFF/LANDING complete at once. */
    default boolean grounded() {
        return false;
    }

    static VehiclePilot of(AbstractVehicle vehicle, AutonomyInfo info) {
        if (vehicle instanceof RotaryWingVehicle rotor) {
            return rotor.multirotor != null ? MultirotorPilot.INSTANCE : HelicopterPilot.INSTANCE;
        }
        if (vehicle instanceof FixedWingVehicle) {
            return new FixedWingPilot(info.minSpeed);
        }
        if (vehicle instanceof WheeledVehicle || vehicle instanceof TrackedVehicle) {
            return GroundPilot.INSTANCE;
        }
        throw new IllegalStateException(vehicle.getVehicleId() + ": autonomy has no pilot for "
                + vehicle.getClass().getSimpleName());
    }
}
