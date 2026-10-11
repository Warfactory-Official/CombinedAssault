package com.wf.combinedassault.vehicle.crew;

import com.google.gson.annotations.SerializedName;

/** Seat duty; pack field {@code crew_roles}, gated by {@code crew.required}. */
public enum CrewRole {
    @SerializedName("driver")
    DRIVER,
    @SerializedName("gunner")
    GUNNER,
    @SerializedName("commander")
    COMMANDER,
    @SerializedName("loader")
    LOADER
}
