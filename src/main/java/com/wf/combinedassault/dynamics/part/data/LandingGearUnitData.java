package com.wf.combinedassault.dynamics.part.data;

import com.wf.combinedassault.vehicle.part.data.PartUnitData;

public class LandingGearUnitData extends PartUnitData {

    protected float dragK;

    public LandingGearUnitData(String id) {
        super(id);
    }

    public LandingGearUnitData(LandingGearUnitPojo pojo) {
        super(pojo);
        this.dragK = pojo.dragK;
    }

    public float getDragK() {
        return dragK;
    }

    public void setDragK(float dragK) {
        this.dragK = dragK;
    }

}
