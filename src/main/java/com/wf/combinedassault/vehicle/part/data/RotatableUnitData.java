package com.wf.combinedassault.vehicle.part.data;

import com.wf.combinedassault.vehicle.pojo.RotInfo;

public class RotatableUnitData extends PartUnitData {

    protected RotInfo rotInfo;

    public RotatableUnitData(String id) {
        super(id);
    }

    public RotatableUnitData(RotatableUnitPojo pojo) {
        super(pojo);
        this.rotInfo = pojo.rotInfo;
    }

    public RotInfo getRotInfo() {
        return rotInfo;
    }

}
