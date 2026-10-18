package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

/** One payload configuration: chosen with the modding tool (free), hardware strapped at the probe node of {@code part}. */
public class LoadoutInfo {

    @SerializedName("id")
    public String id;

    /** Payload part the hardware goes on; null = nothing to fit. */
    @Nullable
    @SerializedName("part")
    public String part;

    /** {@code type} + that payload's config; strapped items are the cost. */
    @Nullable
    @SerializedName("payload")
    public JsonObject payload;

    /** Multirotor default on selection: {@code stabilized} | {@code fpv}; null = unchanged. */
    @Nullable
    @SerializedName("flight_mode")
    public String flightMode;
}
