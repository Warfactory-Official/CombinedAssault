package com.wf.combinedassault.weapon.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;

public class WeaponBayUnitPojo extends PartUnitPojo {

    /** False: hand-worked hatches, open without vehicle power. */
    @SerializedName("need_power")
    public boolean needPower = true;

}
