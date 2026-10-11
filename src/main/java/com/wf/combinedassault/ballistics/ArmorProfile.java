package com.wf.combinedassault.ballistics;

import com.google.gson.annotations.SerializedName;

/**
 * Nominal armour per face, mm RHE. All-zero => unarmoured, every shot falls back to stock damage.
 * Collision boxes are a shell approximation (oversized vs. the real hull) => thickness is authored
 * here rather than measured off geometry.
 */
public class ArmorProfile {

    @SerializedName("front")
    public float front = 0;

    @SerializedName("side")
    public float side = 0;

    @SerializedName("rear")
    public float rear = 0;

    @SerializedName("top")
    public float top = 0;

    @SerializedName("bottom")
    public float bottom = 0;

    /** Degrees from surface normal past which a shot ricochets; overmatch overrides. See {@link ArmorResolver}. */
    @SerializedName("ricochet_angle")
    public float ricochetAngle = 70;

    /** Thickness fraction retained at zero hull health; full health = 1.0x nominal. */
    @SerializedName("wear_floor")
    public float wearFloor = 0.6f;

    public boolean isDefined() {
        return front > 0 || side > 0 || rear > 0 || top > 0 || bottom > 0;
    }

    public float nominal(ArmorFace face) {
        return switch (face) {
            case FRONT -> front;
            case SIDE -> side;
            case REAR -> rear;
            case TOP -> top;
            case BOTTOM -> bottom;
        };
    }

}
