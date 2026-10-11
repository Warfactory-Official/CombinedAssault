package com.wf.combinedassault.api.event;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

public class VehicleMoveEvent extends VehicleEvent {

    public VehicleMoveEvent(AbstractVehicle vehicle) {
        super(vehicle);
    }

}
