package com.wf.combinedassault.render.gem;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.render.model.VehicleModel;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class GemRenderModelLoader {

    private GemRenderModelLoader() {
    }

    public static VehicleModel load(ResourceLocation asset) {
        return new GemRenderVehicleModel(asset, handle(asset));
    }

    /** List position = {@link DamageSkin} variant index; order must match. No {@code _wrecked.png} => reuses {@code _damaged.png}. */
    private static ModelCache.Handle<GemRenderGltfModel> handle(ResourceLocation asset) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Map<ResourceLocation, ResourceLocation> damaged = new HashMap<>();
        Map<ResourceLocation, ResourceLocation> wrecked = new HashMap<>();
        for (ResourceLocation image : images(resources, asset)) {
            ResourceLocation hurt = suffixed(image, "_damaged");
            if (resources.getResource(hurt).isEmpty()) {
                continue;
            }
            ResourceLocation dead = suffixed(image, "_wrecked");
            damaged.put(image, hurt);
            wrecked.put(image, resources.getResource(dead).isPresent() ? dead : hurt);
        }
        if (damaged.isEmpty()) {
            return GemRenderModels.handle(asset);
        }
        return GemRenderModels.variants(asset.withSuffix("_damage_variants"), asset, List.of(Map.of(), damaged, wrecked));
    }

    /** {@code images[].extras.resourceLocation}, the texture locations GemRender substitutes on. */
    private static List<ResourceLocation> images(ResourceManager resources, ResourceLocation asset) {
        List<ResourceLocation> out = new ArrayList<>();
        resources.getResource(asset).ifPresent(resource -> {
            try (Reader reader = resource.openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("images")) {
                    return;
                }
                for (JsonElement image : root.getAsJsonArray("images")) {
                    JsonObject extras = image.getAsJsonObject().getAsJsonObject("extras");
                    if (extras != null && extras.has("resourceLocation")) {
                        out.add(ResourceLocation.parse(extras.get("resourceLocation").getAsString()));
                    }
                }
            } catch (IOException e) {
                CombinedAssault.LOGGER.warn("Could not read {} for damage textures", asset, e);
            }
        });
        return out;
    }

    private static ResourceLocation suffixed(ResourceLocation png, String suffix) {
        String path = png.getPath();
        return ResourceLocation.fromNamespaceAndPath(png.getNamespace(),
                path.substring(0, path.length() - ".png".length()) + suffix + ".png");
    }
}
