package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;

public class WeaponBayUnitPojo extends PartUnitPojo {

    /** False: hand-worked hatches, open without vehicle power. */
    @SerializedName("need_power")
    public boolean needPower = true;

}
