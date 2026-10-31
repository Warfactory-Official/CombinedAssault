package com.wf.combinedassault.weapon.part.data;

import com.wf.combinedassault.vehicle.part.data.PartUnitData;

public class WeaponBayUnitData extends PartUnitData {

    protected boolean needPower;

    public WeaponBayUnitData(WeaponBayUnitPojo pojo) {
        super(pojo);
        this.needPower = pojo.needPower;
    }

    public boolean needPower() {
        return needPower;
    }

}
