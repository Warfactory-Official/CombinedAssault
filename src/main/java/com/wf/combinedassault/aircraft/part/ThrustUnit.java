package com.wf.combinedassault.aircraft.part;

import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.RotatableUnit;

public class ThrustUnit extends RotatableUnit<RotatableUnitData> {

    public ThrustUnit(int index, AbstractVehicle vehicle, RotatableUnitData data) {
        super(index, vehicle, data);
    }

}
