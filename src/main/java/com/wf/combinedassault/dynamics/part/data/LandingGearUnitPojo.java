package com.wf.combinedassault.dynamics.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;

public class LandingGearUnitPojo extends PartUnitPojo {

    @SerializedName("drag_k")
    public float dragK = 0.5f;

}
