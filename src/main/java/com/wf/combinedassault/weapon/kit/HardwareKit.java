package com.wf.combinedassault.weapon.kit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Gun kit, {@code combinedassault/<ns>/hardware/<path>.json}: carried as the {@code hardware} item (or {@code item}),
 * fitted on any part {@code hardpoint} accepting one of its {@code fits}.
 *
 * @param weapons     part {@code weapons} entries the kit brings
 * @param station     weapon part keys ({@link HardwareKits#STATION_KEYS}) over the hardpoint part's own
 * @param shoulderGun item id of the hand-held gun a carrier with a powered shoulder rig holds instead (wflib
 *                    {@code Shoulder}); null = kit cannot be shouldered
 * @param item        another mod's item the kit is carried as (stack kept while fitted, given back); null = {@code hardware}
 * @param replaces    kit hidden while this one loads; its id resolves to this one ({@code fitted}, saves)
 */
public record HardwareKit(ResourceLocation id, ResourceLocation model, JsonArray weapons, Set<String> fits,
                          int installTicks, JsonObject station, @Nullable ResourceLocation shoulderGun,
                          @Nullable Item item, @Nullable ResourceLocation replaces) {

    /** Null: {@code item} names an item no loaded mod registers (kit absent). */
    @Nullable
    public static HardwareKit parse(ResourceLocation id, JsonObject json) {
        Item item = null;
        if (json.has("item")) {
            ResourceLocation itemId = ResourceLocation.parse(GsonHelper.getAsString(json, "item"));
            if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                return null;
            }
            item = BuiltInRegistries.ITEM.get(itemId);
        }
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
                json.has("shoulder_gun") ? ResourceLocation.parse(GsonHelper.getAsString(json, "shoulder_gun")) : null,
                item, json.has("replaces") ? ResourceLocation.parse(GsonHelper.getAsString(json, "replaces")) : null);
    }

    public String nameKey() {
        return "hardware." + id.getNamespace() + "." + id.getPath();
    }

    public Component name() {
        return item != null ? item.getDescription() : Component.translatable(nameKey());
    }

    /** A fresh carried kit. */
    public ItemStack stack() {
        return item != null ? new ItemStack(item) : HardwareItem.of(id);
    }

    public boolean matches(ItemStack stack) {
        if (item != null) {
            return stack.is(item);
        }
        HardwareKit kit = HardwareItem.kit(stack);
        return kit != null && kit.id.equals(id);
    }
}
