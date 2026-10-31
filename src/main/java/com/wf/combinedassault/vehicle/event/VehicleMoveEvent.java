package com.wf.combinedassault.vehicle.event;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

public class VehicleMoveEvent extends VehicleEvent {

    public VehicleMoveEvent(AbstractVehicle vehicle) {
        super(vehicle);
    }

}
