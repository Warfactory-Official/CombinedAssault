package com.wf.combinedassault.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;

/** One glTF link per {@code module_length} along each track path. */
public class TrackConfig {

    /** glTF asset path. */
    @SerializedName("model")
    public ResourceLocation model;

    @SerializedName("module_length")
    public float moduleLength = 6f / 16.0f;
}
