package com.wf.combinedassault.vehicle.crew;

import com.google.gson.annotations.SerializedName;

/** Weapon-station reload mode; active while {@code crew.required}. */
public enum WeaponLoading {
    @SerializedName("self")
    SELF,
    @SerializedName("autoloader")
    AUTOLOADER,
    @SerializedName("manual")
    MANUAL
}
