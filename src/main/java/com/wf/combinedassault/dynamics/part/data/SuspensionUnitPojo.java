package com.wf.combinedassault.dynamics.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;

public class SuspensionUnitPojo extends PartUnitPojo {

    @SerializedName("spring_stiffness")
    public float springStiffness = 200000f;

    @SerializedName("damping")
    public float damping = 800f;

    @SerializedName("rest_length")
    public float restLength = 0.1f;

    @SerializedName("max_compression")
    public float maxCompression = 0.1f;

    @SerializedName("max_extension")
    public float maxExtension = 0.025f;

    /** Null: no droop limit. */
    @SerializedName("max_droop")
    public Float maxDroop;

    /** Landing leg: retracts (bind pose, no reach) while towed; extends to the ground otherwise. */
    @SerializedName("stow_when_towed")
    public boolean stowWhenTowed;

    public SuspensionUnitPojo() {
        this.isSeat = false;
    }

}
