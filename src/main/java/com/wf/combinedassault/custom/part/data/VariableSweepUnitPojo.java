package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;

public class VariableSweepUnitPojo extends RotatableUnitPojo {

    /** Part yaw at full sweep, degrees (rot_info convention). */
    @SerializedName("sweep_angle")
    public float sweepAngle = 0;

    /** Airspeed, blocks/tick: fully forward at or below start, fully swept at or above end. */
    @SerializedName("sweep_start")
    public float sweepStart = 1.5F;

    @SerializedName("sweep_end")
    public float sweepEnd = 3.0F;

}
