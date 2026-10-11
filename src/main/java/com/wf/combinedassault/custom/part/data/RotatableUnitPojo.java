package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.pojo.RotInfo;

public class RotatableUnitPojo extends PartUnitPojo {

    @SerializedName("rot_info")
    public RotInfo rotInfo = new RotInfo();

}
