package com.wf.combinedassault.weapon.kit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Loaded {@link HardwareKit}s (both sides, same packs) and the vehicle def pass expanding each part {@code hardpoint}:
 * part {@code weapons} = accepted kits' weapons (tagged {@code kit}), one kit-scoped {@code mounts} entry per kit.
 */
public final class HardwareKits {

    /** Weapon part keys a kit {@code station} or a hardpoint override may set; anything else => load error. */
    public static final Set<String> STATION_KEYS = Set.of("pose", "dismount_offset",
            "protect_passenger", "passenger_can_use_item", "crew_roles", "rot_info", "bolts", "ejects", "ammo_capacity",
            "firing_mode", "operator_on_weapon_unit",
            "fire_control_sensor_type", "optical_sight_type", "with_stabilizer", "with_focus_locker",
            "with_thermal_imager", "with_night_vision", "with_ir", "loading", "zoom_min", "zoom_max", "crosshair_style",
            "handling", "optic");
    /** Hardpoint override key outside the station: vehicle display name (lang key) while the kit is fitted. */
    public static final String VEHICLE_NAME = "vehicle_name";
    /** Hardpoint override key outside the station: node the kit model grafts under instead of the hardpoint's. */
    public static final String NODE = "node";

    private static volatile Map<ResourceLocation, HardwareKit> kits = Map.of();
    /** Replaced kit id -> its replacement. */
    private static volatile Map<ResourceLocation, ResourceLocation> aliases = Map.of();
    /** Kits whose {@code item} no loaded mod registers. */
    private static volatile Set<ResourceLocation> absent = Set.of();

    private HardwareKits() {
    }

    public static void load(Map<ResourceLocation, JsonObject> defs, List<String> problems) {
        Map<ResourceLocation, HardwareKit> out = new LinkedHashMap<>();
        Set<ResourceLocation> missing = new HashSet<>();
        defs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            try {
                HardwareKit kit = HardwareKit.parse(e.getKey(), e.getValue());
                if (kit == null) {
                    missing.add(e.getKey());
                } else {
                    out.put(e.getKey(), kit);
                }
            } catch (RuntimeException ex) {
                problems.add("hardware " + e.getKey() + ": " + ex.getMessage());
            }
        });
        Map<ResourceLocation, ResourceLocation> alias = new HashMap<>();
        for (HardwareKit kit : List.copyOf(out.values())) {
            if (kit.replaces() != null) {
                if (out.remove(kit.replaces()) == null) {
                    problems.add("hardware " + kit.id() + ": replaces " + kit.replaces() + ", no loaded kit");
                }
                alias.put(kit.replaces(), kit.id());
            }
        }
        kits = Map.copyOf(out);
        aliases = Map.copyOf(alias);
        absent = Set.copyOf(missing);
    }

    /** {@code id}, or the kit replacing it. */
    public static ResourceLocation resolve(ResourceLocation id) {
        return aliases.getOrDefault(id, id);
    }

    @Nullable
    public static HardwareKit get(@Nullable ResourceLocation id) {
        return id == null ? null : kits.get(resolve(id));
    }

    /** Sorted by id. */
    public static List<HardwareKit> all() {
        return kits.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    /** Kits a hardpoint accepting {@code classes} takes, by id. */
    public static List<HardwareKit> accepting(Set<String> classes) {
        return all().stream().filter(kit -> kit.fits().stream().anyMatch(classes::contains)).toList();
    }

    public static void checkStation(JsonObject station, String where) {
        for (String key : station.keySet()) {
            if (!STATION_KEYS.contains(key)) {
                throw new IllegalArgumentException(where + ": " + key + " is no station key; allowed " + STATION_KEYS);
            }
        }
    }

    /** In place, every part with a {@code hardpoint}; throws naming the part. */
    public static void expand(JsonObject vehicle) {
        if (!vehicle.has("parts")) {
            return;
        }
        for (JsonElement element : vehicle.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (part.has("hardpoint")) {
                String id = GsonHelper.getAsString(part, "id", "?");
                try {
                    expandPart(part);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("part " + id + " hardpoint: " + e.getMessage(), e);
                }
            }
        }
    }

    private static void expandPart(JsonObject part) {
        if (!"combinedassault:weapon".equals(GsonHelper.getAsString(part, "type", ""))) {
            throw new IllegalArgumentException("only a combinedassault:weapon part takes kits");
        }
        if (part.has("weapons")) {
            throw new IllegalArgumentException("weapons come from the kits; delete the part's own");
        }
        Hardpoint hardpoint = Hardpoint.parse(part.getAsJsonObject("hardpoint"));
        JsonArray weapons = new JsonArray();
        JsonArray mounts = part.has("mounts") ? part.getAsJsonArray("mounts") : new JsonArray();
        for (HardwareKit kit : hardpoint.kits()) {
            Set<ResourceLocation> seen = new HashSet<>();
            for (JsonElement entry : kit.weapons()) {
                JsonObject weapon = entry.isJsonObject() ? entry.getAsJsonObject().deepCopy() : new JsonObject();
                if (!entry.isJsonObject()) {
                    weapon.addProperty("id", entry.getAsString());
                }
                ResourceLocation weaponId = ResourceLocation.parse(GsonHelper.getAsString(weapon, "id"));
                if (!seen.add(weaponId)) {
                    throw new IllegalArgumentException("kit " + kit.id() + " repeats weapon " + weaponId);
                }
                weapon.addProperty("kit", kit.id().toString());
                if (!weapon.has("save_id")) {
                    weapon.addProperty("save_id", kit.id() + "/" + weaponId);
                }
                weapons.add(weapon);
            }
            JsonObject mount = new JsonObject();
            JsonObject override = hardpoint.overrides().get(kit.id());
            mount.addProperty("model", kit.model().toString());
            mount.addProperty("node", override != null && override.has(NODE) ? override.get(NODE).getAsString() : hardpoint.node());
            mount.addProperty("kit", kit.id().toString());
            mounts.add(mount);
        }
        part.add("weapons", weapons);
        part.add("mounts", mounts);
    }

    /** {@code part} (final json) with the kit's station then the hardpoint's override; objects merge key by key. */
    public static JsonObject station(JsonObject part, HardwareKit kit, @Nullable JsonObject override) {
        JsonObject out = part.deepCopy();
        merge(out, kit.station());
        if (override != null) {
            JsonObject station = override.deepCopy();
            station.remove(VEHICLE_NAME);
            station.remove(NODE);
            merge(out, station);
        }
        return out;
    }

    private static void merge(JsonObject into, JsonObject from) {
        for (Map.Entry<String, JsonElement> e : from.entrySet()) {
            JsonElement at = into.get(e.getKey());
            if (at != null && at.isJsonObject() && e.getValue().isJsonObject()) {
                merge(at.getAsJsonObject(), e.getValue().getAsJsonObject());
            } else {
                into.add(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    /**
     * Hardpoint keys as authored; kits resolved against the loaded set.
     *
     * @param door switchable part id: fitting needs it opened, closing it is refused while a kit is on; null = none
     */
    public record Hardpoint(String node, Set<String> accepts, List<HardwareKit> kits, @Nullable ResourceLocation fitted,
                            Map<ResourceLocation, JsonObject> overrides, @Nullable String door) {

        public static Hardpoint parse(JsonObject json) {
            String node = GsonHelper.getAsString(json, "node");
            Set<String> accepts = new HashSet<>();
            for (JsonElement a : GsonHelper.getAsJsonArray(json, "accepts")) {
                accepts.add(a.getAsString());
            }
            List<HardwareKit> kits = accepting(accepts);
            if (kits.isEmpty()) {
                throw new IllegalArgumentException("no kit fits " + accepts);
            }
            List<ResourceLocation> ids = new ArrayList<>(kits.stream().map(HardwareKit::id).toList());
            ResourceLocation fitted = json.has("fitted") && !json.get("fitted").isJsonNull()
                    ? resolve(ResourceLocation.parse(json.get("fitted").getAsString())) : null;
            if (fitted != null && !ids.contains(fitted)) {
                throw new IllegalArgumentException("fitted " + fitted + " is no accepted kit " + ids);
            }
            Map<ResourceLocation, JsonObject> overrides = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : GsonHelper.getAsJsonObject(json, "overrides", new JsonObject())
                    .entrySet()) {
                ResourceLocation named = ResourceLocation.parse(e.getKey());
                if (absent.contains(named)) {
                    continue;
                }
                ResourceLocation kit = resolve(named);
                if (!ids.contains(kit)) {
                    throw new IllegalArgumentException("override " + kit + " is no accepted kit " + ids);
                }
                JsonObject override = e.getValue().getAsJsonObject();
                JsonObject station = override.deepCopy();
                if (station.has(VEHICLE_NAME) && !(station.remove(VEHICLE_NAME) instanceof JsonPrimitive)) {
                    throw new IllegalArgumentException("override " + kit + ": " + VEHICLE_NAME + " is a lang key");
                }
                if (station.has(NODE) && !(station.remove(NODE) instanceof JsonPrimitive)) {
                    throw new IllegalArgumentException("override " + kit + ": " + NODE + " is a node name");
                }
                checkStation(station, "override " + kit);
                overrides.put(kit, override);
            }
            return new Hardpoint(node, Set.copyOf(accepts), List.copyOf(kits), fitted, Map.copyOf(overrides),
                    json.has("door") ? GsonHelper.getAsString(json, "door") : null);
        }

        public int indexOf(@Nullable ResourceLocation kit) {
            ResourceLocation id = kit == null ? null : resolve(kit);
            for (int i = 0; i < kits.size(); i++) {
                if (kits.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        @Nullable
        public String vehicleName(ResourceLocation kit) {
            JsonObject override = overrides.get(kit);
            return override != null && override.has(VEHICLE_NAME) ? override.get(VEHICLE_NAME).getAsString() : null;
        }
    }
}
