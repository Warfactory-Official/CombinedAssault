package com.wf.combinedassault.aircraft.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitPojo;

public class ControlSurfaceUnitPojo extends RotatableUnitPojo {

    /** Part xRot, degrees, at full stick forward (nose down); signed. */
    @SerializedName("pitch")
    public float pitch;

    /** Part xRot, degrees, at full right roll; signed. */
    @SerializedName("roll")
    public float roll;

    /** Part xRot, degrees, at full right pedal; signed. */
    @SerializedName("yaw")
    public float yaw;

}
