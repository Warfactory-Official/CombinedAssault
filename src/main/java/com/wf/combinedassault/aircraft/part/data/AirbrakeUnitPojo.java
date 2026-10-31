package com.wf.combinedassault.aircraft.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;

public class AirbrakeUnitPojo extends PartUnitPojo {

    @SerializedName("drag_k")
    public float dragK = 0.5f;

}
