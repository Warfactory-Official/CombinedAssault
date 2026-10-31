package com.wf.combinedassault.aircraft.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitPojo;

public class FlapUnitPojo extends RotatableUnitPojo {

    /** Deflection per notch, degrees, first = retracted; part xRot follows at rot_info.x_rot_speed. */
    @SerializedName("stages")
    public float[] stages = {0, 15, 30};

    /** Lift at full deflection = (1 + lift_gain) x clean. */
    @SerializedName("lift_gain")
    public float liftGain = 0.35F;

    /** Added to control drag at full deflection, like an airbrake's drag_k. */
    @SerializedName("drag_k")
    public float dragK = 0.6F;

    /** Stall angle of attack lowered by this at full deflection, degrees. */
    @SerializedName("stall_shift")
    public float stallShift = 3;

    /** Airspeed, blocks/tick; above it deployed flaps retract. */
    @SerializedName("max_speed")
    public float maxSpeed = 2.0F;

}
