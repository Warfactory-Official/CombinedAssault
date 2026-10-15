package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;

public class AirDefensePojo {

    /** Engagement and organic radar range, blocks. */
    @SerializedName("range")
    public double range = 384;

    /** Organic radar antenna height over the mount, blocks. */
    @SerializedName("mast")
    public double mast = 3;

    /** Launch elevation window, degrees above the horizon: lead point outside => clamped. */
    @SerializedName("min_elevation")
    public float minElevation = 15;

    @SerializedName("max_elevation")
    public float maxElevation = 80;

    /** Ticks between the rounds of a salvo. */
    @SerializedName("salvo_spacing")
    public int salvoSpacing = 10;

    /** Ticks after a salvo. */
    @SerializedName("cooldown")
    public int cooldown = 40;

    /** Laser: beam ticks per engagement before the next pick (salvo + spacing unused). */
    @SerializedName("dwell")
    public int dwell = 40;
}
