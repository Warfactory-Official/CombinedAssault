package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One configuration of a vehicle, chosen with the modding tool (free); its hardware goes on at the probe node of
 * {@code part}, paid from the inventory. Exactly one of {@code payload} / {@code weapon} with a {@code part}.
 */
public class LoadoutInfo {

    @SerializedName("id")
    public String id;

    /** Part the hardware goes on; null = nothing to fit. */
    @Nullable
    @SerializedName("part")
    public String part;

    /** Payload part: {@code type} + that payload's config; strapped items are the cost. */
    @Nullable
    @SerializedName("payload")
    public JsonObject payload;

    /** Weapon part: weapon id selected and fitted for {@code install}. */
    @Nullable
    @SerializedName("weapon")
    public String weapon;

    @SerializedName("install")
    public List<Cost> install = List.of();

    /** Multirotor default on selection: {@code stabilized} | {@code fpv}; null = unchanged. */
    @Nullable
    @SerializedName("flight_mode")
    public String flightMode;

    /** {@code item}: item id or {@code #tag}. */
    public static class Cost {
        @SerializedName("item")
        public String item;

        @SerializedName("count")
        public int count = 1;
    }
}
