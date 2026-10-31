package com.wf.combinedassault.vehicle.part;

import org.jetbrains.annotations.UnmodifiableView;
import com.wf.combinedassault.vehicle.part.data.DoorUnitData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.Map;

public class DoorUnit extends SwitchableUnit<DoorUnitData> {

    private PartUnit<?> seatUnitOfDoor;

    public DoorUnit(int index, AbstractVehicle vehicle, DoorUnitData data) {
        super(index, vehicle, data);
    }

    public void combineAndInit(@UnmodifiableView Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        this.seatUnitOfDoor = partUnitsView.get(data.getDoorForSeatId());
    }

    public PartUnit<?> getSeatUnitOfDoor() {
        return seatUnitOfDoor;
    }

}
