package com.wf.combinedassault.camo;

import com.google.gson.JsonObject;
import com.wf.combinedassault.pack.CommonAssetsManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Display ids sharing a vehicle def: the def itself, then its {@code display.variants} keys in file order. */
public final class Finishes {

    private Finishes() {
    }

    /** Empty when {@code current} names no def or its def has no variants. */
    public static List<ResourceLocation> of(ResourceLocation current) {
        CommonAssetsManager.ensureLoaded();
        for (Map.Entry<ResourceLocation, JsonObject> def : CommonAssetsManager.vehicleDefs().entrySet()) {
            JsonObject display = def.getValue().has("display") ? def.getValue().getAsJsonObject("display") : null;
            if (display == null || !display.has("variants")) {
                continue;
            }
            List<ResourceLocation> family = new ArrayList<>();
            family.add(def.getKey());
            display.getAsJsonObject("variants").keySet().forEach(key ->
                    family.add(ResourceLocation.fromNamespaceAndPath(def.getKey().getNamespace(), key)));
            if (family.contains(current)) {
                return family;
            }
        }
        return List.of();
    }

    /** Paint name when the variant sets one, else the display id path. */
    public static Component name(ResourceLocation id) {
        CommonAssetsManager.ensureLoaded();
        for (Map.Entry<ResourceLocation, JsonObject> def : CommonAssetsManager.vehicleDefs().entrySet()) {
            if (!def.getValue().has("display")) {
                continue;
            }
            JsonObject display = def.getValue().getAsJsonObject("display");
            if (id.equals(def.getKey())) {
                return Component.translatable("paint.combinedassault.factory");
            }
            if (display.has("variants") && id.getNamespace().equals(def.getKey().getNamespace())
                    && display.getAsJsonObject("variants").has(id.getPath())) {
                JsonObject variant = display.getAsJsonObject("variants").getAsJsonObject(id.getPath());
                ResourceLocation paint = variant.has("paint") ? ResourceLocation.tryParse(variant.get("paint").getAsString()) : null;
                return paint == null ? Component.literal(id.getPath())
                        : Component.translatable("paint." + paint.getNamespace() + "." + paint.getPath());
            }
        }
        return Component.literal(id.getPath());
    }
}
