package com.wf.combinedassault.ballistics;

import com.google.gson.annotations.SerializedName;
import org.joml.Vector3f;

/** Vehicle-local hit face. +Z forward, matching {@link net.minecraft.world.entity.Entity#getLookAngle()} at zero yaw. */
public enum ArmorFace {

    @SerializedName("front") FRONT,
    @SerializedName("side") SIDE,
    @SerializedName("rear") REAR,
    @SerializedName("top") TOP,
    @SerializedName("bottom") BOTTOM;

    /** Face whose outward normal is {@code worldNormal}. */
    public static ArmorFace ofWorld(com.wf.combinedassault.entity.vehicle.AbstractVehicle vehicle, Vector3f worldNormal) {
        return of(ArmorResolver.toVehicleFrame(vehicle, worldNormal));
    }

    public static ArmorFace of(Vector3f localNormal) {
        float ax = Math.abs(localNormal.x);
        float ay = Math.abs(localNormal.y);
        float az = Math.abs(localNormal.z);
        if (ay >= ax && ay >= az) {
            return localNormal.y >= 0 ? TOP : BOTTOM;
        }
        if (az >= ax) {
            return localNormal.z >= 0 ? FRONT : REAR;
        }
        return SIDE;
    }

}
