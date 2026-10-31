package com.wf.combinedassault.pack.client.vehicle;

import com.wf.combinedassault.render.animation.context.VehicleContext;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

// instanceof needs a reified type; VehicleDisplay<...> parameterized pattern illegal.
public class SimpleVehicleDisplay extends VehicleDisplay<AbstractVehicle, VehicleContext<AbstractVehicle>> {

    public SimpleVehicleDisplay(VehicleDisplayPojo pojo) {
        super(pojo);
    }

}
