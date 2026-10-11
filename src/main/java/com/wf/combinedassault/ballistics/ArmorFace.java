package com.wf.combinedassault.ballistics;

import org.joml.Vector3f;

/** Vehicle-local hit face. +Z forward, matching {@link net.minecraft.world.entity.Entity#getLookAngle()} at zero yaw. */
public enum ArmorFace {

    FRONT,
    SIDE,
    REAR,
    TOP,
    BOTTOM;

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
