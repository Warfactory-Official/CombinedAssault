package com.wf.combinedassault.client.resource;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.ModRegistries;
import com.wf.combinedassault.api.scripts.ScriptContextFactory;
import com.wf.combinedassault.client.resource.vehicle.BaseDisplay;
import com.wf.combinedassault.client.resource.vehicle.VehicleDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonObject;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.pack.CombinedPacks;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class DisplayManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public final Marker marker;
    private final String path;
    private Map<ResourceLocation, BaseDisplay> displayMap = Map.of();
    private final Map<ResourceLocation, List<BaseDisplay>> modelWithVariableDisplay = new ConcurrentHashMap<>();
    private final ScriptManager scriptManager;
    private final ScriptContextFactory scriptContextFactory;

    public DisplayManager(String path,
                         ScriptManager scriptManager,
                         ScriptContextFactory scriptContextFactory) {
        this.path = path;
        this.marker = MarkerManager.getMarker("DisplayManager$" + path);
        this.scriptManager = scriptManager;
        this.scriptContextFactory = scriptContextFactory;
    }

    @NotNull
    @Override
    public Map<ResourceLocation, JsonElement> prepare(@NotNull ResourceManager manager, @NotNull ProfilerFiller pProfiler) {
        Map<ResourceLocation, JsonElement> out = new LinkedHashMap<>();
        switch (path) {
            case "vehicle" -> CommonAssetsManager.vehicleDefs().forEach((id, def) -> {
                JsonObject display = def.has("display") ? def.getAsJsonObject("display") : new JsonObject();
                JsonObject base = new JsonObject();
                base.add("type", def.get("type"));
                base.add("model", def.get("model"));
                copy(display, "icon", base, "slot_texture");
                copy(display, "controller", base, "animation_controller");
                copy(display, "engine", base, "engine");
                copy(display, "cabin_muffle", base, "cabin_muffle");
                copy(display, "tab_index", base, "tab_index");
                copy(display, "description", base, "description");
                copy(display, "paint", base, "paint");
                copy(display, "track", base, "track_config");
                out.put(id, base);
                if (display.has("variants")) {
                    for (var variant : display.getAsJsonObject("variants").entrySet()) {
                        JsonObject copy = base.deepCopy();
                        variant.getValue().getAsJsonObject().entrySet().forEach(e -> copy.add(e.getKey(), e.getValue()));
                        out.put(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), variant.getKey()), copy);
                    }
                }
            });
            case "weapon" -> CommonAssetsManager.weaponDefs().forEach((id, def) -> {
                JsonObject weapon = new JsonObject();
                weapon.addProperty("type", CombinedAssault.MOD_ID + ":weapon");
                out.put(id, weapon);
            });
            case "decoration" -> CombinedPacks.defs("decorations").forEach((id, def) -> {
                JsonObject decoration = new JsonObject();
                decoration.addProperty("type", CombinedAssault.MOD_ID + ":weapon");
                copy(def.json(), "model", decoration, "model");
                copy(def.json(), "icon", decoration, "slot_texture");
                out.put(id, decoration);
            });
            default -> throw new IllegalStateException("display kind " + path);
        }
        return out;
    }

    private static void copy(JsonObject from, String key, JsonObject to, String as) {
        if (from.has(key)) {
            to.add(as, from.get(key));
        }
    }

    @Override
    public void apply(@NotNull Map<ResourceLocation, JsonElement> resources, @NotNull ResourceManager manager, @NotNull ProfilerFiller pProfiler) {
        ImmutableMap.Builder<ResourceLocation, BaseDisplay> displayMapBuilder = ImmutableMap.builder();
        
        for (var displayIdAndDataJson : resources.entrySet()) {
            try {
                var obj = GsonHelper.convertToJsonObject(displayIdAndDataJson.getValue(), "vehicle display");
                String type = GsonHelper.getAsString(obj, "type", "combinedassault:generic");
                ResourceLocation typeId = ResourceLocation.tryParse(type);
                if (typeId == null) {
                    CombinedAssault.LOGGER.warn(marker, "Failed to load vehicle display: {}, invalid type id {}", displayIdAndDataJson.getKey(), type);
                    continue;
                }

                var dataType = ModRegistries.VEHICLE_DISPLAY_TYPE.get(typeId);
                if (dataType == null) {
                    CombinedAssault.LOGGER.warn(marker, "Failed to load vehicle display: {}, unknown type {}", displayIdAndDataJson.getKey(), typeId);
                    continue;
                }

                var display = dataType.parse(displayIdAndDataJson.getValue());
                if (display == null) {
                    CombinedAssault.LOGGER.warn(marker, "Failed to parse vehicle display: {}", displayIdAndDataJson.getKey());
                    continue;
                }

                if (display instanceof VehicleDisplay<?, ?> vehicleDisplay) {
                    vehicleDisplay.initializeAnimationController(scriptManager, scriptContextFactory);
                    if (vehicleDisplay.getCabinDisplay() != null) {
                        vehicleDisplay.getCabinDisplay().initializeAnimationController(scriptManager, scriptContextFactory);
                    }
                }

                displayMapBuilder.put(displayIdAndDataJson.getKey(), display);
                display.setDisplayId(displayIdAndDataJson.getKey());
                if (display.getModelPath() != null) {
                    modelWithVariableDisplay.computeIfAbsent(display.getModelPath(), k -> new ArrayList<>()).add(display);
                }
            } catch (Exception e) {
                CombinedAssault.LOGGER.error(marker, "Failed to load vehicle display: {}", displayIdAndDataJson.getKey(), e);
            }
        }
        displayMap = displayMapBuilder.build();
    }

    @UnmodifiableView
    public Map<ResourceLocation, BaseDisplay> getDisplayMap() {
        return displayMap;
    }

    public Map<ResourceLocation, List<BaseDisplay>> getModelWithVariableDisplay() {
        return modelWithVariableDisplay;
    }

}
