package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;

public class DefenseStats {

    @SerializedName("damage_threshold")
    public float damageThreshold = 50;

    /** m/s; null: 10, aircraft 5. */
    @SerializedName("impact_speed_threshold")
    public Float impactSpeedThreshold;

    @SerializedName("impact_multiplier")
    public float impactMultiplier = 0.1f;

    @SerializedName("damage_transfer_coefficient")
    public float damageTransferCoefficient = 1f;

    /** Scales the mass/armour term of wflib {@code LaserDamage}; 1 = formula. */
    @SerializedName("laser_resistance")
    public float laserResistance = 1f;

}
