package com.wf.combinedassault.custom.part.data;

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
