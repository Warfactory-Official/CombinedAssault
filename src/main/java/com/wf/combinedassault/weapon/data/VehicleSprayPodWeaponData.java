package com.wf.combinedassault.weapon.data;

import com.google.gson.annotations.SerializedName;

/** Fluid tank (mB = {@code max_capacity}) sprayed {@code mb_per_shot} per shot; wflib {@code Sprays} chooses gas / liquid. */
public class VehicleSprayPodWeaponData extends BaseVehicleWeaponData {

    @SerializedName("mb_per_shot")
    public int mbPerShot = 50;

    /** Footprint radius (blocks): gas flood-fill reach / liquid curtain disc. */
    @SerializedName("radius")
    public int radius = 3;

    @Override
    protected boolean needsReload() {
        return false;
    }
}
