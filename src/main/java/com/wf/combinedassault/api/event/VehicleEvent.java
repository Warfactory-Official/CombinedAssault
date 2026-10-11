package com.wf.combinedassault.api.event;

import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

public abstract class VehicleEvent extends EntityEvent implements ICancellableEvent {

    private final AbstractVehicle vehicle;

    public VehicleEvent(AbstractVehicle vehicle) {
        super(vehicle);
        this.vehicle = vehicle;
    }

    public AbstractVehicle getVehicle() {
        return vehicle;
    }

}
