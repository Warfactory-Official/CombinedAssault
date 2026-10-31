package com.wf.combinedassault.weapon.kit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Gun kit, {@code combinedassault/<ns>/hardware/<path>.json}: carried as the {@code hardware} item, fitted on any
 * part {@code hardpoint} accepting one of its {@code fits}.
 *
 * @param weapons part {@code weapons} entries the kit brings
 * @param station weapon part keys ({@link HardwareKits#STATION_KEYS}) over the hardpoint part's own
 * @param shoulderGun item id of the hand-held gun a carrier with a powered shoulder rig holds instead (wflib
 *                    {@code Shoulder}); null = kit cannot be shouldered
 */
public record HardwareKit(ResourceLocation id, ResourceLocation model, JsonArray weapons, Set<String> fits,
                          int installTicks, JsonObject station, @Nullable ResourceLocation shoulderGun) {

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
                Set.copyOf(fits), GsonHelper.getAsInt(json, "install_ticks", 100), station,
                json.has("shoulder_gun") ? ResourceLocation.parse(GsonHelper.getAsString(json, "shoulder_gun")) : null);
    }

    public String nameKey() {
        return "hardware." + id.getNamespace() + "." + id.getPath();
    }
}
