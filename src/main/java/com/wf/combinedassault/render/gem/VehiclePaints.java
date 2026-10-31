package com.wf.combinedassault.render.gem;

import com.google.gson.annotations.SerializedName;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.texture.Paint;
import com.wf.gemrender.texture.PaintArray;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.JsonDataManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.pack.serialize.GsonUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code assets/<ns>/paints/<name>.json} -> {@link PaintArray} layers, id {@code <ns>:<name>}. Named by a
 * display's {@code paint}.
 */
@OnlyIn(Dist.CLIENT)
final class VehiclePaints extends JsonDataManager<VehiclePaints.Pojo> {

    static final VehiclePaints INSTANCE = new VehiclePaints();

    private static final float DEFAULT_TILE = 4.0f;

    private volatile Map<ResourceLocation, Paint> paints = Map.of();

    private VehiclePaints() {
        super(Pojo.class, GsonUtil.GSON, "paints", "VehiclePaint");
    }

    static Paint get(@Nullable ResourceLocation id) {
        return id == null ? Paint.NONE : INSTANCE.paints.getOrDefault(id, Paint.NONE);
    }

    /** The vehicle's display paint; NONE when the model has no mask. */
    static Paint forVehicle(AbstractVehicle vehicle, GemRenderGltfModel gltf) {
        if (!gltf.paintable()) {
            return Paint.NONE;
        }
        return ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId())
                .map(display -> get(display.getPaint()))
                .orElse(Paint.NONE);
    }

    @Override
    public void apply(Map<ResourceLocation, Pojo> pojos, ResourceManager resources, ProfilerFiller profiler) {
        super.apply(pojos, resources, profiler);
        PaintArray.clear();
        Map<ResourceLocation, Paint> built = new HashMap<>();
        pojos.forEach((id, pojo) -> {
            if (pojo.texture != null) {
                built.put(id, PaintArray.texture(pojo.texture, pojo.tile > 0 ? pojo.tile : DEFAULT_TILE));
            } else if (pojo.color != null) {
                built.put(id, PaintArray.solid(Integer.parseInt(pojo.color.substring(pojo.color.startsWith("#") ? 1 : 0), 16)));
            } else {
                CombinedAssault.LOGGER.warn("Paint {} names neither texture nor color", id);
            }
        });
        paints = Map.copyOf(built);
    }

    static final class Pojo {
        /** Tiling texture. */
        @SerializedName("texture")
        ResourceLocation texture;

        /** Blocks per repeat of {@link #texture}. */
        @SerializedName("tile")
        float tile;

        /** {@code #RRGGBB}, when no texture. */
        @SerializedName("color")
        String color;
    }
}
