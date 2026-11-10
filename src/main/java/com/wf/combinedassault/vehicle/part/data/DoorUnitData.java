package com.wf.combinedassault.vehicle.part.data;

import java.util.List;

public class DoorUnitData extends PartUnitData {

    protected String doorForSeatId;
    protected boolean opensOnDismount = true;
    protected List<String> closedWith = List.of();

    public DoorUnitData(String id) {
        super(id);
    }

    public DoorUnitData(DoorUnitPojo pojo) {
        super(pojo);
        this.doorForSeatId = pojo.doorForSeatId;
        this.opensOnDismount = pojo.opensOnDismount == null || pojo.opensOnDismount;
        this.closedWith = pojo.closedWith == null ? List.of() : List.copyOf(pojo.closedWith);
    }

    public String getDoorForSeatId() {
        return doorForSeatId;
    }

    public void setDoorForSeatId(String doorForSeatId) {
        this.doorForSeatId = doorForSeatId;
    }

    public boolean opensOnDismount() {
        return opensOnDismount;
    }

    public List<String> closedWith() {
        return closedWith;
    }

}
