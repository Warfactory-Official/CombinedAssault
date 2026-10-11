package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;

/**
 * One fluid a vehicle burns, and how far it goes on it.
 *
 * <p>{@code fluid}: fluid id ({@code minecraft:lava}) or fluid tag ({@code #c:diesel}). {@code efficiency}
 * scales range, not power: 0.5 burns 2x as fast for same output; 2.0 lasts 2x as long.
 */
public class FuelEntry {

    @SerializedName("fluid")
    public String fluid = "";

    @SerializedName("efficiency")
    public float efficiency = 1f;

    public FuelEntry() {
    }

    public FuelEntry(String fluid, float efficiency) {
        this.fluid = fluid;
        this.efficiency = efficiency;
    }

}
