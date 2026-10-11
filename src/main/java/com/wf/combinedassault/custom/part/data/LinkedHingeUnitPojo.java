package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;

public class LinkedHingeUnitPojo extends RotatableUnitPojo {

    /** Switchable part (door, weapon_bay, landing_gear) whose on state opens this hinge. */
    @SerializedName("link")
    public String link = "";

    /** Part pitch/yaw while the link is on, degrees (rot_info convention). */
    @SerializedName("open_x_rot")
    public float openXRot = 0;

    @SerializedName("open_y_rot")
    public float openYRot = 0;

}
