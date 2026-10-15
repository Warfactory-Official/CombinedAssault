package com.wf.combinedassault.custom.weapon.data;

import com.google.gson.annotations.SerializedName;

/** Directed energy: rated wflib laser damage per tick, reach, vehicle energy per beam tick. */
public class VehicleLaserWeaponData extends BaseVehicleWeaponData {

    @SerializedName("power")
    public float power = 1.0f;

    @SerializedName("range")
    public double range = 96.0;

    @SerializedName("energy_per_tick")
    public float energyPerTick = 0.0005f;

    @Override
    protected boolean needsReload() {
        return false;
    }
}
