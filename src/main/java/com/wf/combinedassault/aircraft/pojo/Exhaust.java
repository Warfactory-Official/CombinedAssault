package com.wf.combinedassault.aircraft.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Jet nozzle, vehicle frame. Client effect only. */
public class Exhaust {

    /** Exit centre. */
    @SerializedName("offset")
    public Vec3 offset;

    @SerializedName("radius")
    public float radius = 0.5F;

    /** Flow, unit after load. */
    @SerializedName("direction")
    public Vec3 direction = new Vec3(0, 0, -1);

    /** Present => turned with the thrust part's pitch about this point (local X axis). */
    @Nullable
    @SerializedName("pivot")
    public Vec3 pivot;

    /** Nozzle glow share: 0 = cold (fan) air. */
    @SerializedName("heat")
    public float heat = 1;

    /** Smoke/haze density, 0 = none. */
    @SerializedName("smoke")
    public float smoke = 0.2F;
}
