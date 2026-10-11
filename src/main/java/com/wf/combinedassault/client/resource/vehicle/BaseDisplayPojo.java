package com.wf.combinedassault.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import com.wf.wflib.audio.EngineSound;
import net.minecraft.resources.ResourceLocation;

public class BaseDisplayPojo {

    /** glTF asset path. */
    @SerializedName("model")
    public ResourceLocation model;

    /** Paint id ({@code assets/<ns>/paints/<name>.json}); glTF with a {@code _paint} mask only. */
    @SerializedName("paint")
    public ResourceLocation paint;

    @SerializedName("slot_texture")
    public ResourceLocation slotTexture;

    @SerializedName("animation_controller")
    public ResourceLocation animationController;

    @SerializedName("engine")
    public EngineSound engine;

    /** Muffle 0..1 heard from a protected seat with its doors shut. */
    @SerializedName("cabin_muffle")
    public float cabinMuffle;

    @SerializedName("description")
    public String description;

    @SerializedName("tab_index")
    public int tabIndex;
}
