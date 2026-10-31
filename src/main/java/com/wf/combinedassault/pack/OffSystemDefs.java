package com.wf.combinedassault.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.core.api.WFSystems;

/** Pack defs naming a vehicle data type or part unit type of a switched-off system are skipped, one warning per system. */
public final class OffSystemDefs {

    /** Type id (path in {@code combinedassault:}) -> owning system. */
    private static final Map<String, String> OWNER = Map.ofEntries(
            Map.entry("vessel_vehicle", "naval"), Map.entry("ship", "naval"),
            Map.entry("rotary_wing_vehicle", "aircraft"), Map.entry("fixed_wing_vehicle", "aircraft"),
            Map.entry("thrust", "aircraft"), Map.entry("variable_sweep", "aircraft"), Map.entry("flaps", "aircraft"),
            Map.entry("airbrake", "aircraft"), Map.entry("radar", "radar"), Map.entry("payload", "uav"));

    private OffSystemDefs() {
    }

    static void skip(Map<ResourceLocation, JsonObject> defs) {
        Map<String, Integer> skipped = new TreeMap<>();
        defs.entrySet().removeIf(e -> {
            String owner = off(e.getValue());
            if (owner == null) {
                return false;
            }
            skipped.merge(owner, 1, Integer::sum);
            return true;
        });
        skipped.forEach((system, count) -> CombinedAssault.LOGGER.warn(
                "combinedassault:{} is off: skipped {} vehicle defs that need it", system, count));
    }

    private static String off(JsonObject def) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        if (def.has("type")) {
            seen.put(def.get("type").getAsString(), true);
        }
        JsonArray parts = def.has("parts") ? def.getAsJsonArray("parts") : new JsonArray();
        for (JsonElement part : parts) {
            JsonObject p = part.getAsJsonObject();
            if (p.has("type")) {
                seen.put(p.get("type").getAsString(), true);
            }
        }
        for (String type : seen.keySet()) {
            String owner = offOwner(ResourceLocation.tryParse(type));
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    /** Switched-off system owning {@code type}, else null. */
    private static String offOwner(ResourceLocation type) {
        String owner = type == null || !type.getNamespace().equals(CombinedAssault.MOD_ID) ? null : OWNER.get(type.getPath());
        return owner != null && !WFSystems.enabled(CombinedAssault.MOD_ID + ":" + owner) ? owner : null;
    }

    public static boolean isOff(ResourceLocation type) {
        return offOwner(type) != null;
    }
}
