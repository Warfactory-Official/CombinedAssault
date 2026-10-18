package com.wf.combinedassault.vehicle.hardware;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Gun kit, {@code combinedassault/<ns>/hardware/<path>.json}: carried as the {@code hardware} item, fitted on any
 * part {@code hardpoint} accepting one of its {@code fits}.
 *
 * @param weapons part {@code weapons} entries the kit brings
 * @param station weapon part keys ({@link HardwareKits#STATION_KEYS}) over the hardpoint part's own
 */
public record HardwareKit(ResourceLocation id, ResourceLocation model, JsonArray weapons, Set<String> fits,
                          int installTicks, JsonObject station) {

    public static HardwareKit parse(ResourceLocation id, JsonObject json) {
        JsonArray weapons = GsonHelper.getAsJsonArray(json, "weapons");
        if (weapons.isEmpty()) {
            throw new IllegalArgumentException("no weapons");
        }
        Set<String> fits = new LinkedHashSet<>();
        for (JsonElement fit : GsonHelper.getAsJsonArray(json, "fits")) {
            fits.add(fit.getAsString());
        }
        if (fits.isEmpty()) {
            throw new IllegalArgumentException("fits nothing");
        }
        JsonObject station = GsonHelper.getAsJsonObject(json, "station", new JsonObject());
        HardwareKits.checkStation(station, "station");
        return new HardwareKit(id, ResourceLocation.parse(GsonHelper.getAsString(json, "model")), weapons,
                Set.copyOf(fits), GsonHelper.getAsInt(json, "install_ticks", 100), station);
    }

    public String nameKey() {
        return "hardware." + id.getNamespace() + "." + id.getPath();
    }
}
