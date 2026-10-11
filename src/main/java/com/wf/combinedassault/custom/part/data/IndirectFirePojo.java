package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;

public class IndirectFirePojo {

    /** Operator may designate targets on the map. */
    @SerializedName("map")
    public boolean map = false;

    /** Degrees above the horizon a self-guided round leaves at (no ballistic solution). */
    @SerializedName("launch_elevation")
    public float launchElevation = 45;
}
