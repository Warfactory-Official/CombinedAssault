package com.wf.combinedassault.aircraft.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitPojo;

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
