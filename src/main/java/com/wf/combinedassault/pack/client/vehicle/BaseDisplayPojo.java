package com.wf.combinedassault.pack.client.vehicle;

import com.google.gson.annotations.SerializedName;
import com.wf.wflib.audio.api.EngineSound;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public class BaseDisplayPojo {

    /** glTF asset path. */
    @SerializedName("model")
    public ResourceLocation model;

    /** Paint id ({@code assets/<ns>/paints/<name>.json}); glTF with a {@code _paint} mask only. */
    @SerializedName("paint")
    public ResourceLocation paint;

    /** glTF texture location -> replacement png of the same size; absent => authored textures. */
    @SerializedName("reskin")
    public Map<ResourceLocation, ResourceLocation> reskin;

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
