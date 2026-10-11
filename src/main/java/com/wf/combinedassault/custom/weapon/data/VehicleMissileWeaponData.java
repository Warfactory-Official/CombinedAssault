package com.wf.combinedassault.custom.weapon.data;

import com.google.gson.annotations.SerializedName;

/** Launch limits + rack; the missile itself is the loaded WFLib round. */
public class VehicleMissileWeaponData extends BaseVehicleWeaponData {

    @SerializedName("x_rot_max")
    private float xRotMax = 10f;

    @SerializedName("x_rot_min")
    private float xRotMin = -10f;

    @SerializedName("y_rot_max")
    private float yRotMax = 10f;

    @SerializedName("y_rot_min")
    private float yRotMin = -10f;

    /** Missile holder id to draw rounds from first; empty => magazine only. */
    @SerializedName("missile_holder")
    private String missileHolder = "";

    /** Fires racked rounds and nothing else: ammo = rounds racked, no reload. */
    @SerializedName("holder_only")
    private boolean holderOnly = false;

    @Override
    protected boolean needsReload() {
        return !holderOnly;
    }

    public float getXRotMax() {
        return xRotMax;
    }

    public float getXRotMin() {
        return xRotMin;
    }

    public float getYRotMax() {
        return yRotMax;
    }

    public float getYRotMin() {
        return yRotMin;
    }

    public String getMissileHolder() {
        return missileHolder;
    }

    public boolean isHolderOnly() {
        return holderOnly;
    }

}
