package com.wf.combinedassault.weapon.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

/** Spent-case port of the bolt with the same index; pitch frame like {@link Bolt#offset}. */
public class Eject {

    @SerializedName("offset")
    public Vec3 offset;

    /** Unit throw direction. */
    @SerializedName("direction")
    public Vec3 direction;

    /** Throw speed, blocks/s. */
    @SerializedName("speed")
    public float speed;

    public Eject(Vec3 offset, Vec3 direction, float speed) {
        this.offset = offset;
        this.direction = direction;
        this.speed = speed;
    }
}
