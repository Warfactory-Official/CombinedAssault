package com.wf.combinedassault.weapon.data;

import com.google.gson.annotations.SerializedName;

/** Drill, saw, plough: works the blocks of the collider carrying the {@code dig} tag {@link #dig}. */
public class VehicleToolWeaponData extends BaseVehicleWeaponData {

    /** {@code dig.id} of the collider tag. */
    @SerializedName("dig")
    public String dig;

    /** {@code hold}: works while the fire key is down. {@code toggle}: a press starts it, the next stops it. */
    @SerializedName("mode")
    public String mode = "hold";

    @SerializedName("energy_per_tick")
    public float energyPerTick = 0.0f;

    @Override
    protected boolean needsReload() {
        return false;
    }

    @Override
    public void loaded() {
        super.loaded();
        if (dig == null || dig.isEmpty()) {
            throw new IllegalStateException("tool weapon: no dig");
        }
        if (!mode.equals("hold") && !mode.equals("toggle")) {
            throw new IllegalStateException("tool weapon: mode " + mode);
        }
    }
}
