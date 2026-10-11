package com.wf.combinedassault.tow;

import com.google.gson.annotations.SerializedName;

/**
 * HITCH: joint, trailer heading from geometry (one articulated vehicle). CABLE: slack rope, pulls only once taut.
 * SLING: helicopter hook; load hangs from its top centre, rope-length constraint with momentum exchange.
 */
public enum TowMode {

    @SerializedName("hitch")
    HITCH,

    @SerializedName("cable")
    CABLE,

    @SerializedName("sling")
    SLING;

    /** Synced {@code TOW_HITCH} = hitch index * {@link #COUNT} + ordinal. */
    public static final int COUNT = values().length;

    /** Rope modes: length paid out, slack until taut. */
    public boolean rope() {
        return this != HITCH;
    }

    public String lowerName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

}
