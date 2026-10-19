package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.energy.EnergyType;

import java.util.ArrayList;
import java.util.List;

public class EnergyInfo {

    /** Energy unit = 1 kg fuel = 1 mB (upstream's fuel can pours 1:1). */
    public static final int MB_PER_UNIT = 1;
    public static final int DEFAULT_FE_PER_UNIT = 20;
    public static final int DEFAULT_CHARGE_RATE = 4096;

    @SerializedName("energy_type")
    public String energyType = "fuel";

    @SerializedName("energy_density")
    public float energyDensity = 1f;

    @SerializedName("energy_capacity")
    public float energyCapacity = 80f;

    @SerializedName("energy_consumption_per_tick")
    public float energyConsumptionPerTick = 0.001f;

    /** Fluids this vehicle burns. Empty falls back to the {@code fuelNameWhiteList} config. */
    @SerializedName("fuels")
    public List<FuelEntry> fuels = new ArrayList<>();

    /** Tank size in mB. Overrides {@code energy_capacity}. */
    @SerializedName("tank_capacity")
    public int tankCapacity = 0;

    /** FE worth one energy unit. Sets how much charge an electric vehicle's buffer holds. */
    @SerializedName("fe_per_unit")
    public int fePerUnit = 0;

    /** Ceiling on how fast charge goes in, in FE/t. */
    @SerializedName("charge_rate")
    public int chargeRate = 0;

    /** Takes a swappable battery item (FE or GT); drains into the tank regardless of {@link #energyType}. */
    @SerializedName("battery_slot")
    public boolean batterySlot = false;

    @SerializedName("engine_particle_offsets")
    public List<Vec3> engineParticleOffsets = new ArrayList<>();

    public EnergyType type() {
        return EnergyType.parse(energyType);
    }

    public boolean isElectric() {
        return type() == EnergyType.ELECTRIC;
    }

    /** Capacity in energy units, whichever way the pack spelled it. */
    public float capacityUnits() {
        return tankCapacity > 0 ? (float) tankCapacity / MB_PER_UNIT : Math.max(0, energyCapacity);
    }

    public int tankCapacityMb() {
        return tankCapacity > 0 ? tankCapacity : Math.round(Math.max(0, energyCapacity) * MB_PER_UNIT);
    }

    public int fePerUnit() {
        return fePerUnit > 0 ? fePerUnit : DEFAULT_FE_PER_UNIT;
    }

    public int feCapacity() {
        return (int) Math.min(Integer.MAX_VALUE, (long) (capacityUnits() * fePerUnit()));
    }

    public int chargeRateFe() {
        return chargeRate > 0 ? chargeRate : DEFAULT_CHARGE_RATE;
    }

}
