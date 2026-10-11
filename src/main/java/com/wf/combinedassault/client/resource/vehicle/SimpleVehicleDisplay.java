package com.wf.combinedassault.client.resource.vehicle;

import com.wf.combinedassault.client.render.animation.context.VehicleContext;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

// instanceof needs a reified type; VehicleDisplay<...> parameterized pattern illegal.
public class SimpleVehicleDisplay extends VehicleDisplay<AbstractVehicle, VehicleContext<AbstractVehicle>> {

    public SimpleVehicleDisplay(VehicleDisplayPojo pojo) {
        super(pojo);
    }

}
