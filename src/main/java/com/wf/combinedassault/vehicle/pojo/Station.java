package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Holder station in its part frame (rest): origin = round centre, +Z = nose. */
public class Station {

    @SerializedName("offset")
    public Vec3 offset;

    /** Round length the station takes; launch point = origin - length / 2 along +Z. */
    @SerializedName("length")
    public float length;

    /** Bolt convention: x_rot + = nose down, y_rot + = nose to -X. */
    @SerializedName("x_rot")
    public float xRot;

    @SerializedName("y_rot")
    public float yRot;

    @SerializedName("z_rot")
    public float zRot;

    public Quaternionf rotation() {
        return new Quaternionf().rotateYXZ((float) Math.toRadians(-yRot), (float) Math.toRadians(xRot),
                (float) Math.toRadians(zRot));
    }
}
