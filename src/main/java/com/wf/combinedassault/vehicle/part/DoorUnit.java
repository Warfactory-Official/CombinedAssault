package com.wf.combinedassault.vehicle.part;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.data.DoorUnitData;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;

public class DoorUnit extends SwitchableUnit<DoorUnitData> {

    private PartUnit<?> seatUnitOfDoor;
    private List<PartUnit<?>> closedWith = List.of();

    public DoorUnit(int index, AbstractVehicle vehicle, DoorUnitData data) {
        super(index, vehicle, data);
    }

    public void combineAndInit(@UnmodifiableView Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        this.seatUnitOfDoor = partUnitsView.get(data.getDoorForSeatId());
        this.closedWith = data.closedWith().stream().<PartUnit<?>>map(id -> {
            PartUnit<?> seat = partUnitsView.get(id);
            if (seat == null || !seat.getData().isSeat()) {
                throw new IllegalArgumentException(getId() + ": closed_with " + id + " is not a seat");
            }
            return seat;
        }).toList();
    }

    public PartUnit<?> getSeatUnitOfDoor() {
        return seatUnitOfDoor;
    }

    public List<PartUnit<?>> closedWith() {
        return closedWith;
    }

}
