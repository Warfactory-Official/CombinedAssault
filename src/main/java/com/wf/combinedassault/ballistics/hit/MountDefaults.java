package com.wf.combinedassault.ballistics.hit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.pack.part.PartUnitEntry;
import com.wf.combinedassault.weapon.part.data.MissileHolderUnitData;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.physics.api.DeckFrame;
import com.wf.combinedassault.weapon.pojo.Bolt;
import com.wf.combinedassault.weapon.pojo.Eject;
import com.wf.combinedassault.weapon.pojo.Station;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Socket-role nodes ({@link NodeRole.Kind#SOCKET}; interface = target, {@code combinedassault.index}) -> vehicle def
 * defaults (pack format doc, Sockets).
 * A key the json already has wins; a list wins whole. Unknown field or target => the vehicle fails to load.
 */
public final class MountDefaults {

    /** Json value within this of the glTF one => {@link Result#repeated}. */
    private static final double SAME = 1.0e-3;
    private static final double ROUND = 1.0e5;
    /** Below this of +Y, a panel edge is horizontal: the panel lies flat and {@code up} is the edge nearest +Z. */
    private static final float FLAT = 0.1f;
    private static final String WEAPON = "combinedassault:weapon";
    private static final String TRACK = "combinedassault:track";
    private static final String SUSPENSION = "combinedassault:suspension";
    private static final String MISSILE_HOLDER = "combinedassault:missile_holder";
    /** Track outline vertex weld, blocks. */
    private static final float WELD = 1.0e-4f;

    /**
     * @param repeated   json keys equal to what the glTF gives: redundant
     * @param overridden json keys that differ from the glTF
     * @param relative   for {@link #resolve} once the vehicle is parsed
     */
    public record Result(JsonObject vehicle, List<String> repeated, List<String> overridden, List<Relative> relative) {
    }

    private final String vehicleId;
    private final JsonObject vehicle;
    private final ModelMounts mounts;
    private final Map<String, JsonObject> parts = new LinkedHashMap<>();
    private final Map<String, TreeMap<Integer, JsonElement>> lists = new LinkedHashMap<>();
    private final List<String> repeated = new ArrayList<>();
    private final List<String> overridden = new ArrayList<>();
    private final List<Relative> relative = new ArrayList<>();

    private MountDefaults(String vehicleId, JsonObject vehicle, ModelMounts mounts) {
        this.vehicleId = vehicleId;
        this.vehicle = vehicle;
        this.mounts = mounts;
        if (vehicle.has("parts")) {
            for (JsonElement part : vehicle.getAsJsonArray("parts")) {
                parts.put(part.getAsJsonObject().get("id").getAsString(), part.getAsJsonObject());
            }
        }
    }

    public static Result apply(String vehicleId, JsonObject source, ModelMounts mounts) {
        MountDefaults defaults = new MountDefaults(vehicleId, source.deepCopy(), mounts);
        for (ModelMounts.Mount mount : mounts.mounts()) {
            defaults.accept(mount);
        }
        defaults.frames();
        defaults.roadWheels();
        defaults.flushLists();
        defaults.centerOfMass();
        return new Result(defaults.vehicle, List.copyOf(defaults.repeated), List.copyOf(defaults.overridden),
                List.copyOf(defaults.relative));
    }

    private void accept(ModelMounts.Mount mount) {
        String field = mount.role().key();
        String target = mount.target();
        Vector3f origin = mount.origin();
        switch (field) {
            case "seat" -> {
                JsonObject part = part(mount, target);
                put(part, "seat_offset", vec(origin), target + ".seat_offset");
                put(part, "seat_rot", angle(euler(mount.rotation())[0]), target + ".seat_rot");
            }
            case "dismount" -> put(part(mount, target), "dismount_offset", vec(origin), target + ".dismount_offset");
            case "view" -> {
                JsonObject part = part(mount, target);
                if (weapon(part)) {
                    relative.add(new Relative(target, "view", 0, mount));
                } else {
                    put(part, "owner_view_offset", vec(origin), target + ".owner_view_offset");
                }
            }
            case "sight", "sight_lens" -> {
                if (!weapon(part(mount, target))) {
                    throw fail(mount, field + " on part " + target + ", which is not a weapon");
                }
                relative.add(new Relative(target, field, 0, mount));
            }
            case "bolt" -> bolt(mount, target);
            case "eject" -> eject(mount, target);
            case "station" -> station(mount, target);
            case "era", "slat" -> addon(mount, field, target);
            case "mount_point" -> {
                JsonObject point = entry(vehicle, "mount_points", target);
                float[] euler = euler(mount.rotation());
                put(point, "offset", vec(origin), "mount_points." + target + ".offset");
                put(point, "yaw", angle(euler[0]), "mount_points." + target + ".yaw");
                put(point, "pitch", angle(euler[1]), "mount_points." + target + ".pitch");
                put(point, "roll", angle(euler[2]), "mount_points." + target + ".roll");
            }
            case "hitch" -> put(entry(object(vehicle, "towing"), "hitches", target), "offset", vec(origin),
                    "hitches." + target + ".offset");
            case "towable", "axle" -> {
                none(mount, target);
                String key = field.equals("towable") ? "offset" : "axle";
                put(object(object(vehicle, "towing"), "towable"), key, vec(origin), "towable." + key);
            }
            case "camera", "camera_zoomed" -> {
                none(mount, target);
                String key = field.equals("camera") ? "third_person_center_offset" : "third_person_center_offset_zoomed";
                put(object(vehicle, "view_info"), key, vec(origin), "view_info." + key);
            }
            case "cargo_bay" -> {
                none(mount, target);
                JsonObject cargo = object(vehicle, "cargo");
                put(cargo, "bay_center", vec(origin), "cargo.bay_center");
                put(cargo, "bay_size", vec(mount.scale()), "cargo.bay_size");
            }
            case "center_of_mass" -> {
                none(mount, target);
                put(object(vehicle, "physics_info"), "center", vec(origin), "physics_info.center");
            }
            case "pivot" -> {
                none(mount, target);
                put(vehicle, "center_offset", vec(origin), "center_offset");
            }
            case "track" -> track(mount, target);
            case "exhaust" -> list(mount, "energy_info/engine_particle_offsets", target, vec(origin));
            case "propeller" -> {
                JsonArray at = vec(origin);
                at.add(round((mount.scale().x + mount.scale().y + mount.scale().z) / 3));
                list(mount, "attributes/propellers", target, at);
            }
            case "vortex" -> list(mount, "attributes/vortex_offsets", target, vec(origin));
            case "smoke" -> list(mount, "attributes/aerobatic_smoke_offsets", target, vec(origin));
            case "afterburner" -> {
                JsonArray at = vec(origin);
                float scale = (mount.scale().x + mount.scale().y + mount.scale().z) / 3;
                if (Math.abs(scale - 1) > SAME) {
                    at.add(round(scale));
                }
                list(mount, "attributes/afterburner_offsets", target, at);
            }
            default -> throw new IllegalStateException(field);
        }
    }

    /** No json, no socket: volume centroid of the solid colliders, rest pose (uniform density). */
    private void centerOfMass() {
        JsonObject physics = object(vehicle, "physics_info");
        if (physics.has("center")) {
            return;
        }
        Vector3f sum = new Vector3f();
        float volume = 0;
        for (ModelRig.Collider collider : mounts.rig().colliders()) {
            if (collider.solid()) {
                float v = collider.hull().volume();
                sum.add(new Vector3f(collider.hull().centroid()).mul(v));
                volume += v;
            }
        }
        if (volume > 0) {
            physics.add("center", vec(sum.div(volume)));
        }
    }

    /**
     * Suspension part frame => {@code render_bone} (visual travel) = frame node name; tracks, doors: none. Frame keys ->
     * {@code rot_info}. Parts the def lacks: skipped.
     */
    private void frames() {
        for (ModelRig.Frame frame : mounts.rig().frames()) {
            JsonObject part = parts.get(frame.part());
            if (part == null) {
                continue;
            }
            if (frame.role() == NodeRole.PART && part.has("type")
                    && part.get("type").getAsString().equals(SUSPENSION)) {
                put(part, "render_bone", new JsonPrimitive(frame.name()), frame.part() + ".render_bone");
            }
            for (String key : frame.meta().keySet()) {
                if (key.startsWith("x_rot_") || key.startsWith("y_rot_")) {
                    if (frame.role() == NodeRole.PITCH && key.startsWith("y_rot_")) {
                        throw new IllegalArgumentException(vehicleId + ": pitch frame " + frame.name() + " sets " + key
                                + "; yaw limits go on the part frame");
                    }
                    put(object(part, "rot_info"), key, number(frame.meta().get(key).getAsFloat()),
                            frame.part() + ".rot_info." + key);
                }
            }
        }
    }

    /** Mesh outline (edges used by one triangle), winding order, from the first triangle's outline edge: closed path. */
    private void track(ModelMounts.Mount mount, String part) {
        JsonObject json = part(mount, part);
        if (!json.has("type") || !json.get("type").getAsString().equals(TRACK)) {
            throw fail(mount, "track on part " + part + ", which is not a track");
        }
        float[] t = mount.triangles();
        List<Vector3f> points = new ArrayList<>();
        int[] index = new int[t.length / 3];
        for (int k = 0; k < index.length; k++) {
            Vector3f v = new Vector3f(t[k * 3], t[k * 3 + 1], t[k * 3 + 2]);
            int found = -1;
            for (int p = 0; p < points.size() && found < 0; p++) {
                if (points.get(p).distanceSquared(v) < WELD * WELD) {
                    found = p;
                }
            }
            if (found < 0) {
                points.add(v);
                found = points.size() - 1;
            }
            index[k] = found;
        }
        Map<Long, Integer> uses = new LinkedHashMap<>();
        for (int k = 0; k < index.length; k++) {
            int a = index[k], b = index[k / 3 * 3 + (k + 1) % 3];
            uses.merge((long) Math.min(a, b) << 32 | Math.max(a, b), 1, Integer::sum);
        }
        Map<Integer, Integer> next = new LinkedHashMap<>();
        for (int k = 0; k < index.length; k++) {
            int a = index[k], b = index[k / 3 * 3 + (k + 1) % 3];
            if (a != b && uses.get((long) Math.min(a, b) << 32 | Math.max(a, b)) == 1 && next.put(a, b) != null) {
                throw fail(mount, "outline branches at " + points.get(a));
            }
        }
        if (next.size() < 3) {
            throw fail(mount, "is no polygon; a track is a face (or fan) whose outline is the path");
        }
        JsonArray path = new JsonArray();
        int start = next.keySet().iterator().next();
        int at = start;
        do {
            path.add(vec(points.get(at)));
            at = next.getOrDefault(at, -1);
        } while (at != start && at >= 0 && path.size() <= next.size());
        if (at != start || path.size() != next.size()) {
            throw fail(mount, "outline is not one closed loop");
        }
        String key = "parts/" + part + "/tracks";
        if (lists.computeIfAbsent(key, k -> new TreeMap<>()).put(Math.max(mount.index(), 0), path) != null) {
            throw fail(mount, "repeats index " + Math.max(mount.index(), 0));
        }
    }

    /** Road wheel -> glTF track path whose mean x is nearest: {@code road_wheels} entry of that track part. */
    private void roadWheels() {
        for (ModelMounts.Wheel wheel : mounts.wheels()) {
            String part = null;
            int track = -1;
            double best = Double.POSITIVE_INFINITY;
            for (var list : lists.entrySet()) {
                String key = list.getKey();
                if (!key.startsWith("parts/") || !key.endsWith("/tracks")) {
                    continue;
                }
                for (var path : list.getValue().entrySet()) {
                    double x = 0;
                    for (JsonElement point : path.getValue().getAsJsonArray()) {
                        x += point.getAsJsonArray().get(0).getAsDouble();
                    }
                    double off = Math.abs(x / path.getValue().getAsJsonArray().size() - wheel.centre().x);
                    if (off < best) {
                        best = off;
                        part = key.substring("parts/".length(), key.length() - "/tracks".length());
                        track = list.getValue().headMap(path.getKey()).size();
                    }
                }
            }
            if (part == null) {
                throw new IllegalArgumentException(vehicleId + ": road_wheel " + wheel.name() + " but no track socket");
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("node", wheel.name());
            entry.addProperty("track", track);
            entry.add("center", vec(wheel.centre()));
            TreeMap<Integer, JsonElement> list = lists.computeIfAbsent("parts/" + part + "/road_wheels", k -> new TreeMap<>());
            list.put(list.size(), entry);
        }
    }

    /** Origin = breech; +Z = bore; Z scale = barrel length. Frame: the part's pitch node, else its yaw node. */
    private void bolt(ModelMounts.Mount mount, String part) {
        int index = Math.max(mount.index(), 0);
        if (!parts.containsKey(part)) {
            throw fail(mount, "names no part; parts are " + parts.keySet());
        }
        if (!weapon(parts.get(part))) {
            throw fail(mount, "bolt on part " + part + ", which is not a weapon");
        }
        for (Relative other : relative) {
            if (other.field.equals("bolt") && other.part.equals(part) && other.index == index
                    && java.util.Objects.equals(other.mount.kit(), mount.kit())) {
                throw fail(mount, "repeats bolt " + index + " of part " + part);
            }
        }
        relative.add(new Relative(part, "bolt", index, mount));
    }

    /** Origin = case port; +Z = throw; Z scale = speed (blocks/s). Index = its bolt's. Frame: as the bolt. */
    private void eject(ModelMounts.Mount mount, String part) {
        int index = Math.max(mount.index(), 0);
        if (!parts.containsKey(part) || !weapon(parts.get(part))) {
            throw fail(mount, "eject on " + part + ", which is no weapon part");
        }
        for (Relative other : relative) {
            if (other.field.equals("eject") && other.part.equals(part) && other.index == index
                    && java.util.Objects.equals(other.mount.kit(), mount.kit())) {
                throw fail(mount, "repeats eject " + index + " of part " + part);
            }
        }
        relative.add(new Relative(part, "eject", index, mount));
    }

    /** Holder station: origin = round centre, +Z = nose, Z scale = length. Frame: the holder's part node. */
    private void station(ModelMounts.Mount mount, String part) {
        JsonObject json = part(mount, part);
        if (!json.has("type") || !json.get("type").getAsString().equals(MISSILE_HOLDER)) {
            throw fail(mount, "station on part " + part + ", which is not a missile_holder");
        }
        int index = Math.max(mount.index(), 0);
        for (Relative other : relative) {
            if (other.field.equals("station") && other.part.equals(part) && other.index == index) {
                throw fail(mount, "repeats station " + index + " of part " + part);
            }
        }
        relative.add(new Relative(part, "station", index, mount));
    }

    /**
     * Weapon view / sight / bolt / eject, holder station: frames only the parsed structure has: view, sight = origin - part pivot (yaw bone);
     * bolt, eject = in the pitch bone's rest frame (bolt: origin = breech, +Z = bore, Z scale = {@code barrel_length}).
     * station = in the holder part bone's rest frame.
     */
    public record Relative(String part, String field, int index, ModelMounts.Mount mount) {
    }

    /** @param values part id -> json keys the json left out; {@code kit_bolts} / {@code kit_ejects} / {@code kit_sights}: kit id -> bolts / ejects / sight */
    public record Resolved(Map<String, JsonObject> values, List<String> repeated, List<String> overridden) {
    }

    /** {@code vehicle}: the json as authored (a key it has wins). */
    public static Resolved resolve(String vehicleId, JsonObject vehicle, List<Relative> relative,
                                   BaseVehicleData<?> data) {
        if (relative.isEmpty()) {
            return new Resolved(Map.of(), List.of(), List.of());
        }
        Map<String, JsonObject> given = new LinkedHashMap<>();
        for (JsonElement part : vehicle.getAsJsonArray("parts")) {
            given.put(part.getAsJsonObject().get("id").getAsString(), part.getAsJsonObject());
        }
        Map<String, WeaponUnitData> weapons = new LinkedHashMap<>();
        Map<String, MissileHolderUnitData> holders = new LinkedHashMap<>();
        for (PartUnitEntry<?, ?> entry : data.getParts()) {
            if (entry.data() instanceof WeaponUnitData weapon) {
                weapons.put(weapon.getId(), weapon);
            } else if (entry.data() instanceof MissileHolderUnitData holder) {
                holders.put(holder.getId(), holder);
            }
        }
        Map<String, TreeMap<Integer, JsonElement>> stations = new LinkedHashMap<>();
        Map<String, JsonObject> values = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, JsonElement>> bolts = new LinkedHashMap<>();
        Map<String, Map<String, TreeMap<Integer, JsonElement>>> kitBolts = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, JsonElement>> ejects = new LinkedHashMap<>();
        Map<String, Map<String, TreeMap<Integer, JsonElement>>> kitEjects = new LinkedHashMap<>();
        for (Relative r : relative) {
            WeaponUnitData weapon = weapons.get(r.part);
            String where = vehicleId + ": glTF node " + r.mount.name() + " ";
            if (r.field.equals("station")) {
                VehicleCubeGroup group = holders.get(r.part).getRawStructureGroup();
                if (group == null) {
                    throw new IllegalArgumentException(where + "part " + r.part + " has no part frame");
                }
                VehicleCubeGroup.GlobalTransform rest = group.globalTransform();
                Quaternionf inverse = new Quaternionf(rest.rotation()).conjugate();
                Vector3f offset = inverse.transform(new Vector3f(r.mount.origin()).sub(rest.offset().toVector3f()));
                Vector3f angles = inverse.mul(r.mount.rotation(), new Quaternionf()).getEulerAnglesYXZ(new Vector3f());
                JsonObject station = new JsonObject();
                station.add("offset", vec(offset));
                station.add("length", number(r.mount.scale().z));
                station.add("x_rot", angle((float) Math.toDegrees(angles.x)));
                station.add("y_rot", angle((float) Math.toDegrees(-angles.y)));
                station.add("z_rot", angle((float) Math.toDegrees(angles.z)));
                stations.computeIfAbsent(r.part, k -> new TreeMap<>()).put(r.index, station);
                continue;
            }
            if (r.field.equals("eject")) {
                VehicleCubeGroup group = weapon.getRawXTurnGroup();
                if (group == null) {
                    throw new IllegalArgumentException(where + "part " + r.part + " has no structure bone to eject from");
                }
                VehicleCubeGroup.GlobalTransform rest = group.globalTransform();
                Quaternionf inverse = new Quaternionf(rest.rotation()).conjugate();
                JsonObject eject = new JsonObject();
                eject.add("offset", vec(inverse.transform(new Vector3f(r.mount.origin()).sub(rest.offset().toVector3f()))));
                eject.add("direction", vec(inverse.mul(r.mount.rotation(), new Quaternionf())
                        .transform(new Vector3f(0, 0, 1))));
                eject.add("speed", number(r.mount.scale().z));
                (r.mount.kit() == null ? ejects.computeIfAbsent(r.part, k -> new TreeMap<>())
                        : kitEjects.computeIfAbsent(r.part, k -> new LinkedHashMap<>())
                                .computeIfAbsent(r.mount.kit(), k -> new TreeMap<>())).put(r.index, eject);
                continue;
            }
            if (r.field.equals("sight_lens")) {
                JsonObject part = values.computeIfAbsent(r.part, k -> new JsonObject());
                if (r.mount.kit() == null) {
                    part.addProperty("sight_lens", r.mount.name());
                } else {
                    if (!part.has("kit_lenses")) {
                        part.add("kit_lenses", new JsonObject());
                    }
                    part.getAsJsonObject("kit_lenses").addProperty(r.mount.kit(), r.mount.name());
                }
                continue;
            }
            if (r.field.equals("bolt")) {
                VehicleCubeGroup group = weapon.getRawXTurnGroup();
                if (group == null) {
                    throw new IllegalArgumentException(where + "part " + r.part + " has no structure bone to fire from");
                }
                VehicleCubeGroup.GlobalTransform rest = group.globalTransform();
                Quaternionf inverse = new Quaternionf(rest.rotation()).conjugate();
                Vector3f offset = inverse.transform(new Vector3f(r.mount.origin()).sub(rest.offset().toVector3f()));
                Vector3f angles = inverse.mul(r.mount.rotation(), new Quaternionf()).getEulerAnglesYXZ(new Vector3f());
                JsonObject bolt = new JsonObject();
                bolt.add("offset", vec(offset));
                bolt.add("barrel_length", number(r.mount.scale().z));
                bolt.add("x_rot", angle((float) Math.toDegrees(angles.x)));
                bolt.add("y_rot", angle((float) Math.toDegrees(-angles.y)));
                (r.mount.kit() == null ? bolts.computeIfAbsent(r.part, k -> new TreeMap<>())
                        : kitBolts.computeIfAbsent(r.part, k -> new LinkedHashMap<>())
                                .computeIfAbsent(r.mount.kit(), k -> new TreeMap<>())).put(r.index, bolt);
            } else {
                Vector3f pivot = weapon.getPivotOffset().toVector3f();
                JsonElement offset = vec(new Vector3f(r.mount.origin()).sub(pivot));
                JsonObject part = values.computeIfAbsent(r.part, k -> new JsonObject());
                if (r.mount.kit() != null) {
                    if (!part.has("kit_sights")) {
                        part.add("kit_sights", new JsonObject());
                    }
                    part.getAsJsonObject("kit_sights").add(r.mount.kit(), offset);
                } else {
                    part.add(r.field.equals("view") ? "operator_view_offset" : "optical_sight_offset", offset);
                }
            }
        }
        stations.forEach((part, byIndex) -> {
            JsonArray list = new JsonArray();
            byIndex.values().forEach(list::add);
            values.computeIfAbsent(part, k -> new JsonObject()).add("stations", list);
        });
        ejects.forEach((part, byIndex) -> values.computeIfAbsent(part, k -> new JsonObject())
                .add("ejects", ejectList(vehicleId, part, byIndex)));
        kitEjects.forEach((part, byKit) -> {
            JsonObject lists = new JsonObject();
            byKit.forEach((kit, byIndex) -> lists.add(kit, ejectList(vehicleId, part + " kit " + kit, byIndex)));
            values.computeIfAbsent(part, k -> new JsonObject()).add("kit_ejects", lists);
        });
        bolts.forEach((part, byIndex) -> {
            JsonArray list = new JsonArray();
            byIndex.values().forEach(list::add);
            values.computeIfAbsent(part, k -> new JsonObject()).add("bolts", list);
        });
        kitBolts.forEach((part, byKit) -> {
            JsonObject lists = new JsonObject();
            byKit.forEach((kit, byIndex) -> {
                JsonArray list = new JsonArray();
                byIndex.values().forEach(list::add);
                lists.add(kit, list);
            });
            values.computeIfAbsent(part, k -> new JsonObject()).add("kit_bolts", lists);
        });
        List<String> repeated = new ArrayList<>();
        List<String> overridden = new ArrayList<>();
        for (var part : values.entrySet()) {
            JsonObject json = given.get(part.getKey());
            for (String key : List.copyOf(part.getValue().keySet())) {
                if (json.has(key)) {
                    (same(json.get(key), part.getValue().get(key)) ? repeated : overridden).add(part.getKey() + "." + key);
                    part.getValue().remove(key);
                }
            }
        }
        return new Resolved(values, repeated, overridden);
    }

    private static JsonArray ejectList(String vehicleId, String part, TreeMap<Integer, JsonElement> byIndex) {
        if (byIndex.lastKey() != byIndex.size() - 1) {
            throw new IllegalArgumentException(vehicleId + ": part " + part + " ejects " + byIndex.keySet()
                    + "; indices run 0.." + (byIndex.size() - 1));
        }
        JsonArray list = new JsonArray();
        byIndex.values().forEach(list::add);
        return list;
    }

    /** Resolved values into {@code data}'s weapon / holder parts. */
    public static void apply(BaseVehicleData<?> data, Map<String, JsonObject> values) {
        for (PartUnitEntry<?, ?> entry : data.getParts()) {
            JsonObject keys = values.get(entry.data().getId());
            if (keys != null && keys.has("stations") && entry.data() instanceof MissileHolderUnitData holder) {
                holder.applyStations(List.of(GsonUtil.GSON.fromJson(keys.get("stations"), Station[].class)));
            }
            if (keys != null && entry.data() instanceof WeaponUnitData weapon) {
                weapon.applySockets(
                        keys.has("optical_sight_offset") ? GsonUtil.GSON.fromJson(keys.get("optical_sight_offset"), Vec3.class) : null,
                        keys.has("operator_view_offset") ? GsonUtil.GSON.fromJson(keys.get("operator_view_offset"), Vec3.class) : null,
                        keys.has("bolts") ? List.of(GsonUtil.GSON.fromJson(keys.get("bolts"), Bolt[].class)) : null);
                if (keys.has("ejects")) {
                    weapon.applyEjects(List.of(GsonUtil.GSON.fromJson(keys.get("ejects"), Eject[].class)));
                }
                if (keys.has("kit_ejects")) {
                    Map<ResourceLocation, List<Eject>> byKit = new LinkedHashMap<>();
                    keys.getAsJsonObject("kit_ejects").entrySet().forEach(e -> byKit.put(
                            ResourceLocation.parse(e.getKey()), List.of(GsonUtil.GSON.fromJson(e.getValue(), Eject[].class))));
                    weapon.applyKitEjects(byKit);
                }
                if (keys.has("kit_bolts")) {
                    Map<ResourceLocation, List<Bolt>> byKit = new LinkedHashMap<>();
                    keys.getAsJsonObject("kit_bolts").entrySet().forEach(e -> byKit.put(
                            ResourceLocation.parse(e.getKey()), List.of(GsonUtil.GSON.fromJson(e.getValue(), Bolt[].class))));
                    weapon.applyKitBolts(byKit);
                }
                if (keys.has("sight_lens") || keys.has("kit_lenses")) {
                    Map<ResourceLocation, String> byKit = new LinkedHashMap<>();
                    if (keys.has("kit_lenses")) {
                        keys.getAsJsonObject("kit_lenses").entrySet().forEach(e -> byKit.put(
                                ResourceLocation.parse(e.getKey()), e.getValue().getAsString()));
                    }
                    weapon.applyLenses(keys.has("sight_lens") ? keys.get("sight_lens").getAsString() : null, byKit);
                }
                if (keys.has("kit_sights")) {
                    Map<ResourceLocation, Vec3> byKit = new LinkedHashMap<>();
                    keys.getAsJsonObject("kit_sights").entrySet().forEach(e -> byKit.put(
                            ResourceLocation.parse(e.getKey()), GsonUtil.GSON.fromJson(e.getValue(), Vec3.class)));
                    weapon.applyKitSights(byKit);
                }
            }
        }
    }

    /** Copy of {@code vehicle} with {@code values} in its parts: what the game ends up reading. */
    public static JsonObject merge(JsonObject vehicle, Map<String, JsonObject> values) {
        JsonObject out = vehicle.deepCopy();
        for (JsonElement part : out.getAsJsonArray("parts")) {
            JsonObject keys = values.get(part.getAsJsonObject().get("id").getAsString());
            if (keys != null) {
                keys.entrySet().forEach(e -> part.getAsJsonObject().add(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /**
     * Quad mesh: normal = area-weighted winding normal; up = the edge nearest +Y (flat panel: nearest +Z);
     * width, height, centre = extents along the edges.
     */
    private void addon(ModelMounts.Mount mount, String kind, String target) {
        if (target.isEmpty()) {
            throw fail(mount, "needs an id: combinedassaultInterface");
        }
        float[] t = mount.triangles();
        if (t.length == 0) {
            throw fail(mount, "is an empty; an addon panel is a quad mesh");
        }
        Vector3f n = new Vector3f();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        for (int i = 0; i < t.length; i += 9) {
            a.set(t[i + 3] - t[i], t[i + 4] - t[i + 1], t[i + 5] - t[i + 2]);
            b.set(t[i + 6] - t[i], t[i + 7] - t[i + 1], t[i + 8] - t[i + 2]);
            n.add(a.cross(b));
        }
        if (n.lengthSquared() < 1.0e-12f) {
            throw fail(mount, "has no area");
        }
        n.normalize();
        Vector3f up = edgeNearest(t, n, new Vector3f(0, 1, 0));
        if (up == null) {
            up = edgeNearest(t, n, new Vector3f(0, 0, 1));
        }
        if (up == null) {
            throw fail(mount, "has no edge to take up from");
        }
        Vector3f u = new Vector3f(up).cross(n);
        float uLo = Float.MAX_VALUE, uHi = -Float.MAX_VALUE, vLo = Float.MAX_VALUE, vHi = -Float.MAX_VALUE, depth = 0;
        for (int i = 0; i < t.length; i += 3) {
            a.set(t[i], t[i + 1], t[i + 2]);
            uLo = Math.min(uLo, a.dot(u));
            uHi = Math.max(uHi, a.dot(u));
            vLo = Math.min(vLo, a.dot(up));
            vHi = Math.max(vHi, a.dot(up));
            depth += a.dot(n);
        }
        depth /= t.length / 3f;
        Vector3f centre = new Vector3f(u).mul((uLo + uHi) / 2).add(new Vector3f(up).mul((vLo + vHi) / 2))
                .add(new Vector3f(n).mul(depth));

        JsonObject entry = entry(vehicle, "addon_armor", target);
        String label = "addon_armor." + target + ".";
        put(entry, "kind", new JsonPrimitive(kind), label + "kind");
        put(entry, "bone", new JsonPrimitive(mounts.frameName(mount.node())), label + "bone");
        if (entry.has("surface")) {
            JsonObject surface = entry.getAsJsonObject("surface");
            put(surface, "center", vec(centre), label + "surface.center");
            put(surface, "normal", vec(n), label + "surface.normal");
            put(surface, "up", vec(up), label + "surface.up");
        } else {
            put(entry, "center", vec(centre), label + "center");
            put(entry, "normal", vec(n), label + "normal");
            put(entry, "up", vec(up), label + "up");
        }
        put(entry, "width", number(uHi - uLo), label + "width");
        put(entry, "height", number(vHi - vLo), label + "height");
    }

    /** Triangle edge in the plane most aligned with {@code axis}, signed toward it; null if all are within FLAT of normal to it. */
    private static Vector3f edgeNearest(float[] t, Vector3f n, Vector3f axis) {
        Vector3f best = null;
        float bestDot = FLAT;
        Vector3f e = new Vector3f();
        for (int i = 0; i < t.length; i += 9) {
            for (int k = 0; k < 3; k++) {
                int p = i + k * 3;
                int q = i + (k + 1) % 3 * 3;
                e.set(t[q] - t[p], t[q + 1] - t[p + 1], t[q + 2] - t[p + 2]);
                e.sub(new Vector3f(n).mul(e.dot(n)));
                if (e.lengthSquared() < 1.0e-8f) {
                    continue;
                }
                e.normalize();
                float dot = Math.abs(e.dot(axis));
                if (dot > bestDot + 1.0e-6f) {
                    bestDot = dot;
                    best = new Vector3f(e).mul(Math.signum(e.dot(axis)));
                }
            }
        }
        return best;
    }

    private void list(ModelMounts.Mount mount, String path, String target, JsonElement value) {
        none(mount, target);
        int index = Math.max(mount.index(), 0);
        if (lists.computeIfAbsent(path, k -> new TreeMap<>()).put(index, value) != null) {
            throw fail(mount, "repeats index " + index);
        }
    }

    private void flushLists() {
        for (var entry : lists.entrySet()) {
            String[] path = entry.getKey().split("/");
            JsonObject owner = path[0].equals("parts") ? parts.get(path[1]) : object(vehicle, path[0]);
            JsonArray values = new JsonArray();
            entry.getValue().values().forEach(values::add);
            put(owner, path[path.length - 1], values, entry.getKey().replace('/', '.'));
        }
    }

    private void put(JsonObject owner, String key, JsonElement value, String label) {
        if (!owner.has(key)) {
            owner.add(key, value);
        } else if (same(owner.get(key), value)) {
            repeated.add(label);
        } else {
            overridden.add(label);
        }
    }

    /** Json side may carry keys the glTF does not give. */
    static boolean same(JsonElement json, JsonElement gltf) {
        if (gltf.isJsonPrimitive() && gltf.getAsJsonPrimitive().isNumber()) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()
                    && Math.abs(json.getAsDouble() - gltf.getAsDouble()) <= SAME;
        }
        if (gltf.isJsonArray()) {
            if (!json.isJsonArray() || json.getAsJsonArray().size() != gltf.getAsJsonArray().size()) {
                return false;
            }
            for (int i = 0; i < gltf.getAsJsonArray().size(); i++) {
                if (!same(json.getAsJsonArray().get(i), gltf.getAsJsonArray().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (gltf.isJsonObject()) {
            if (!json.isJsonObject()) {
                return false;
            }
            for (var entry : gltf.getAsJsonObject().entrySet()) {
                JsonElement other = json.getAsJsonObject().get(entry.getKey());
                if (other == null ? !isZero(entry.getValue()) : !same(other, entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return json.equals(gltf);
    }

    /** A json object leaving out a zero field (bolt {@code x_rot}) still says zero. */
    private static boolean isZero(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Math.abs(value.getAsDouble()) <= SAME;
    }

    private JsonObject part(ModelMounts.Mount mount, String id) {
        JsonObject part = parts.get(id);
        if (part == null) {
            throw fail(mount, "names no part; parts are " + parts.keySet());
        }
        return part;
    }

    private static boolean weapon(JsonObject part) {
        return part.has("type") && part.get("type").getAsString().equals(WEAPON);
    }

    private void none(ModelMounts.Mount mount, String target) {
        if (!target.isEmpty()) {
            throw fail(mount, "takes no id");
        }
    }

    private static JsonObject entry(JsonObject owner, String key, String id) {
        if (!owner.has(key)) {
            owner.add(key, new JsonArray());
        }
        for (JsonElement element : owner.getAsJsonArray(key)) {
            if (element.getAsJsonObject().has("id") && element.getAsJsonObject().get("id").getAsString().equals(id)) {
                return element.getAsJsonObject();
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        owner.getAsJsonArray(key).add(entry);
        return entry;
    }

    private static JsonObject object(JsonObject owner, String key) {
        if (!owner.has(key)) {
            owner.add(key, new JsonObject());
        }
        return owner.getAsJsonObject(key);
    }

    private static float[] euler(Quaternionf rotation) {
        return DeckFrame.toEulerYXZ(rotation, new Matrix3f(), new float[3]);
    }

    private static JsonArray vec(Vector3f v) {
        JsonArray array = new JsonArray();
        array.add(round(v.x));
        array.add(round(v.y));
        array.add(round(v.z));
        return array;
    }

    private static JsonPrimitive number(float value) {
        return new JsonPrimitive(round(value));
    }

    /** Degrees in (-180, 180]: a json 180 must not read as a change from -180. */
    private static JsonPrimitive angle(float degrees) {
        double wrapped = ((round(degrees) + 180) % 360 + 360) % 360 - 180;
        return new JsonPrimitive(wrapped == -180 ? 180.0 : wrapped + 0.0);
    }

    /** Zero kept unsigned: {@code -0.0} would read as a change. */
    private static double round(float value) {
        return Math.round(value * ROUND) / ROUND + 0.0;
    }

    private IllegalArgumentException fail(ModelMounts.Mount mount, String why) {
        return new IllegalArgumentException(vehicleId + ": glTF node " + mount.name() + " (" + mount.role().key() + ") "
                + why);
    }
}
