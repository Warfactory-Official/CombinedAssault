package com.wf.combinedassault.api.event;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.List;

/** Fires after sampling, before impact solve consumes the points. List is mutable by listeners. */
public class VehicleCollectCollisionEvent extends VehicleEvent {

    private final List<VehicleCubeOBB.CubePoint> touchPoints;

    public VehicleCollectCollisionEvent(AbstractVehicle vehicle,  List<VehicleCubeOBB.CubePoint> touchPoints) {
        super(vehicle);
        this.touchPoints = touchPoints;
    }

    public List<VehicleCubeOBB.CubePoint> getTouchPoints() {
        return touchPoints;
    }

}
