package com.wf.combinedassault.weapon.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.wflib.sight.api.Optic;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Station {@code optic}: picture-in-picture sight on the {@code sight_lens} mesh. */
public final class OpticSpec implements Optic {
    @SerializedName("zoom")
    private List<Float> zoom = List.of();

    @SerializedName("reticle")
    @Nullable
    private ResourceLocation reticle;

    @SerializedName("reticle_size")
    private float reticleSize;

    @SerializedName("reticle_zoom")
    private float reticleZoom;

    @SerializedName("mask")
    @Nullable
    private ResourceLocation mask;

    @SerializedName("eye_relief")
    @Nullable
    private Float eyeRelief;

    /** Eye -> objective front along the sight line, m: picture pass origin (in front of the kit's own mesh). */
    @SerializedName("objective")
    private float objective;

    public float objective() {
        return objective;
    }

    @Override
    public List<Float> zoom() {
        return zoom;
    }

    @Override
    @Nullable
    public ResourceLocation reticle() {
        return reticle;
    }

    @Override
    public float reticleSize() {
        return reticleSize;
    }

    @Override
    public float reticleZoom() {
        return reticleZoom;
    }

    @Override
    @Nullable
    public ResourceLocation mask() {
        return mask;
    }

    @Override
    @Nullable
    public Float eyeRelief() {
        return eyeRelief;
    }
}
