package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

public class Bolt {

    /** Weapon pivot offset relative to the weapon station's pivot. */
    @SerializedName("offset")
    public Vec3 offset;

    /** Distance from projectile spawn point to barrel pivot. */
    @SerializedName("barrel_length")
    public float barrelLength;

    /** Barrel's off-axis X rotation relative to weapon station forward. */
    @SerializedName("x_rot")
    public float xRot;

    /** Barrel's off-axis Y rotation relative to weapon station forward. */
    @SerializedName("y_rot")
    public float yRot;

    public Bolt(Vec3 offset, float barrelLength, float xRot, float yRot) {
        this.offset = offset;
        this.barrelLength = barrelLength;
        this.xRot = xRot;
        this.yRot = yRot;
    }

}
