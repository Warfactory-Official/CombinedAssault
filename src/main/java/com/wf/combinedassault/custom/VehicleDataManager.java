package com.wf.combinedassault.custom;

import com.wf.combinedassault.custom.part.PartUnitEntry;
import com.wf.combinedassault.custom.part.data.PartMount;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.ModRegistries;
import com.wf.combinedassault.api.custom.IVehicleDataManager;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.vehicle.hit.ModelData;
import com.wf.combinedassault.vehicle.hit.ModelMounts;
import com.wf.combinedassault.vehicle.pojo.WeaponInfo;
import com.wf.combinedassault.vehicle.hit.MountDefaults;
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
    VehicleDataManager(Map<ResourceLocation, JsonObject> defs, Map<ResourceLocation, ModelData> models,
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
        var dataType = typeId == null ? null : ModRegistries.VEHICLE_DATA_TYPE.get(typeId);
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
                if (mount.weapon() != null && !(entry.data() instanceof WeaponUnitData unit
                        && unit.getWeapons().stream().anyMatch(info -> mount.weapon().equals(info.id)))) {
                    throw new IllegalArgumentException("part " + entry.data().getId() + " mount " + mount.model()
                            + " names weapon " + mount.weapon() + ", not one of the part's weapons");
                }
                if (mount.name() != null && mount.weapon() == null) {
                    throw new IllegalArgumentException("part " + entry.data().getId() + " mount " + mount.model()
                            + " has a name but no weapon");
                }
            }
        }
        Loadouts.validate((BaseVehicleData<?>) data);
        MountDefaults.Resolved weapons = MountDefaults.resolve(vehicleId.toString(), obj, mounted.relative(), data);
        MountDefaults.apply(data, weapons.values());
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

}
