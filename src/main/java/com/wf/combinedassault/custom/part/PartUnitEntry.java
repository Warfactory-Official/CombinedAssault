package com.wf.combinedassault.custom.part;

import com.wf.combinedassault.custom.part.data.PartUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

public record PartUnitEntry<T extends PartUnit<D>, D extends PartUnitData>(
        PartUnitType<T, D> type,
        D data
) {
    public T create(int index, AbstractVehicle vehicle) {
        return type.factory().create(index, vehicle, data);
    }
}
