package com.wf.combinedassault.pack;

import com.wf.combinedassault.pack.part.PartUnitEntry;
import com.wf.combinedassault.vehicle.part.data.PartMount;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.weapon.kit.HardwareKit;
import com.wf.combinedassault.weapon.kit.HardwareKits;
import com.wf.combinedassault.weapon.kit.Loadouts;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import com.wf.combinedassault.weapon.pojo.WeaponInfo;
import com.wf.combinedassault.ballistics.hit.MountDefaults;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class VehicleDataManager implements IVehicleDataManager {

    public static final Marker MARKER = MarkerManager.getMarker("VehicleDataManager");
    private final Map<ResourceLocation, BaseVehicleData> indexes;

    /** {@code defs}: vehicle defs as authored; {@code models}: one per def. */
    public VehicleDataManager(Map<ResourceLocation, JsonObject> defs, Map<ResourceLocation, ModelData> models,
                       List<String> problems) {
        ImmutableMap.Builder<ResourceLocation, BaseVehicleData> builder = ImmutableMap.builder();
        for (var entry : defs.entrySet()) {
            ResourceLocation vehicleId = entry.getKey();
            try {
                BaseVehicleData data = parse(vehicleId, entry.getValue(), models.get(vehicleId));
                if (data == null) {
                    problems.add("vehicle " + vehicleId + ": its type refused it");
                    continue;
                }
                builder.put(vehicleId, data);
            } catch (Exception e) {
                problems.add("vehicle " + vehicleId + ": " + e.getMessage());
                CombinedAssault.LOGGER.error(MARKER, "vehicle {}", vehicleId, e);
            }
        }
        indexes = builder.build();
    }

    @Override
    public Optional<BaseVehicleData> getVehicleData(ResourceLocation id) {
        return Optional.ofNullable(indexes.get(id));
    }

    @Override
    public Map<ResourceLocation, BaseVehicleData> getVehicleData() {
        return indexes;
    }

    @Nullable
    private static BaseVehicleData parse(ResourceLocation vehicleId, JsonObject obj, ModelData model) {
        String type = GsonHelper.getAsString(obj, "type", CombinedAssault.MOD_ID + ":generic");
        ResourceLocation typeId = ResourceLocation.tryParse(type);
        var dataType = typeId == null ? null : PackTypes.VEHICLE_DATA.get(typeId);
        if (dataType == null) {
            throw new IllegalArgumentException("unknown type " + type);
        }
        MountDefaults.Result mounted = MountDefaults.apply(vehicleId.toString(), obj, model.mounts());
        BaseVehicleData data;
        BaseVehicleData.MODEL.set(model);
        try {
            data = dataType.parse(mounted.vehicle());
        } finally {
            BaseVehicleData.MODEL.remove();
        }
        if (data == null) {
            return null;
        }
        Set<String> nodes = model.mounts().links().stream().map(ModelMounts.Link::name).collect(Collectors.toSet());
        for (PartUnitEntry<?, ?> entry : ((BaseVehicleData<?>) data).getParts()) {
            if (entry.data() instanceof WeaponUnitData unit) {
                for (WeaponInfo info : unit.getWeapons()) {
                    for (String bone : info.roundBones) {
                        if (!nodes.contains(bone)) {
                            throw new IllegalArgumentException("weapon " + info.id + " round_bones names " + bone
                                    + ", not a node of " + model.source());
                        }
                    }
                }
            }
            for (PartMount mount : entry.data().getMounts()) {
                if (mount.kit() != null && !(entry.data() instanceof WeaponUnitData unit
                        && unit.getHardpoint() != null && unit.getHardpoint().indexOf(mount.kit()) >= 0)) {
                    throw new IllegalArgumentException("part " + entry.data().getId() + " mount " + mount.model()
                            + " names kit " + mount.kit() + ", which its hardpoint does not take");
                }
            }
        }
        Loadouts.validate((BaseVehicleData<?>) data);
        MountDefaults.Resolved weapons = MountDefaults.resolve(vehicleId.toString(), obj, mounted.relative(), data);
        MountDefaults.apply(data, weapons.values());
        variants(MountDefaults.merge(mounted.vehicle(), weapons.values()), (BaseVehicleData<?>) data);
        List<String> repeated = new ArrayList<>(mounted.repeated());
        repeated.addAll(weapons.repeated());
        List<String> overridden = new ArrayList<>(mounted.overridden());
        overridden.addAll(weapons.overridden());
        if (!repeated.isEmpty()) {
            CombinedAssault.LOGGER.warn(MARKER, "{}: def repeats its glTF sockets, delete: {}", vehicleId, repeated);
        }
        if (!overridden.isEmpty()) {
            CombinedAssault.LOGGER.info(MARKER, "{}: def overrides its glTF sockets: {}", vehicleId, overridden);
        }
        data.setVehicleId(vehicleId);
        data.bindHitModel();
        data.setName(Component.translatable("entity." + vehicleId.getNamespace() + "." + vehicleId.getPath()));
        return data;
    }

    /** Each hardpoint part: one data per accepted kit, its final json with the kit's station over it. */
    private static void variants(JsonObject merged, BaseVehicleData<?> data) {
        Map<String, JsonObject> parts = new java.util.HashMap<>();
        merged.getAsJsonArray("parts").forEach(p -> parts.put(p.getAsJsonObject().get("id").getAsString(),
                p.getAsJsonObject()));
        for (PartUnitEntry<?, ?> entry : data.getParts()) {
            if (!(entry.data() instanceof WeaponUnitData unit) || unit.getHardpoint() == null) {
                continue;
            }
            HardwareKits.Hardpoint hardpoint = unit.getHardpoint();
            Map<ResourceLocation, WeaponUnitData> out = new java.util.LinkedHashMap<>();
            for (HardwareKit kit : hardpoint.kits()) {
                JsonObject station = HardwareKits.station(parts.get(unit.getId()), kit, hardpoint.overrides().get(kit.id()));
                WeaponUnitData variant = (WeaponUnitData) entry.type().parseAndCreate(station).data();
                variant.initFrames(unit.getRawStructureGroup(), unit.getRawPitchGroup());
                boolean ownBolts = kit.station().has("bolts") || hardpoint.overrides().containsKey(kit.id())
                        && hardpoint.overrides().get(kit.id()).has("bolts");
                variant.applySockets(null, null, ownBolts ? null : unit.getBolts(kit.id()));
                if (!kit.station().has("ejects")) {
                    variant.applyEjects(unit.getEjects(kit.id()));
                }
                out.put(kit.id(), variant);
            }
            unit.applyVariants(out);
        }
    }

}
