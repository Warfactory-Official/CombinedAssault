package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Vehicle hold: vehicles driven into the bay are stowed while {@code door} is shut. */
public class CargoInfo {

    public static final CargoInfo NONE = new CargoInfo();

    /** Switchable part closing the hold. */
    @SerializedName("door")
    public String door = "";

    /** Kg, summed over stowed vehicles' current mass. Zero: no limit. */
    @SerializedName("max_mass")
    public float maxMass = 0;

    /** Bay box centre, vehicle frame (from its origin); glTF socket {@code cargo_bay} origin. */
    @Nullable
    @SerializedName("bay_center")
    public Vec3 bayCenter;

    /** Bay box full size; {@code cargo_bay} socket scale. */
    @Nullable
    @SerializedName("bay_size")
    public Vec3 baySize;

    public boolean enabled() {
        return !door.isEmpty() && bayCenter != null && baySize != null;
    }

}
