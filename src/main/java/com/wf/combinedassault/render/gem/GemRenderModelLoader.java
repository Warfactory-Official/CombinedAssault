package com.wf.combinedassault.render.gem;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.render.model.VehicleModel;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.geom.api.Glb;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class GemRenderModelLoader {

    private GemRenderModelLoader() {
    }

    public static VehicleModel load(ResourceLocation asset) {
        return load(asset, Map.of());
    }

    /** {@code reskin}: glTF texture location -> replacement png; damage tiles read as {@code <replacement>_damaged / _wrecked}. */
    public static VehicleModel load(ResourceLocation asset, Map<ResourceLocation, ResourceLocation> reskin) {
        return new GemRenderVehicleModel(asset, handle(asset, reskin));
    }

    /** List position = {@link DamageSkin} variant index; order must match. No {@code _wrecked.png} => reuses {@code _damaged.png}. */
    private static ModelCache.Handle<GemRenderGltfModel> handle(ResourceLocation asset,
                                                                 Map<ResourceLocation, ResourceLocation> reskin) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Map<ResourceLocation, ResourceLocation> damaged = new HashMap<>();
        Map<ResourceLocation, ResourceLocation> wrecked = new HashMap<>();
        List<ResourceLocation> images = images(resources, asset);
        reskin.forEach((from, to) -> {
            if (!images.contains(from)) {
                throw new IllegalArgumentException(asset + " has no texture " + from + " to reskin; has " + images);
            }
            if (resources.getResource(to).isEmpty()) {
                throw new IllegalArgumentException("reskin texture " + to + " of " + asset + " is missing");
            }
        });
        for (ResourceLocation image : images) {
            ResourceLocation worn = reskin.getOrDefault(image, image);
            ResourceLocation hurt = suffixed(worn, "_damaged");
            if (resources.getResource(hurt).isEmpty()) {
                if (worn != image) {
                    damaged.put(image, worn);
                    wrecked.put(image, worn);
                }
                continue;
            }
            ResourceLocation dead = suffixed(worn, "_wrecked");
            damaged.put(image, hurt);
            wrecked.put(image, resources.getResource(dead).isPresent() ? dead : hurt);
        }
        if (reskin.isEmpty() && damaged.isEmpty()) {
            return GemRenderModels.handle(asset);
        }
        String suffix = reskin.isEmpty() ? "_damage_variants" : "_reskin_" + Integer.toHexString(reskin.hashCode());
        return GemRenderModels.variants(asset.withSuffix(suffix), asset, List.of(reskin, damaged, wrecked));
    }

    /** {@code images[].extras.resourceLocation}, the texture locations GemRender substitutes on. */
    private static List<ResourceLocation> images(ResourceManager resources, ResourceLocation asset) {
        List<ResourceLocation> out = new ArrayList<>();
        resources.getResource(asset).ifPresent(resource -> {
            try (InputStream stream = resource.open()) {
                JsonObject root = Glb.json(stream.readAllBytes());
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
