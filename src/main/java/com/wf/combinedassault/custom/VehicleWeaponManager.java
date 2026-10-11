package com.wf.combinedassault.custom;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.ModRegistries;
import com.wf.combinedassault.api.custom.IVehicleWeaponManager;
import com.wf.combinedassault.custom.weapon.VehicleWeaponIndex;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VehicleWeaponManager implements IVehicleWeaponManager {

    private final Map<ResourceLocation, VehicleWeaponIndex<?, ?>> indexes;

    VehicleWeaponManager(Map<ResourceLocation, JsonObject> defs, List<String> problems) {
        indexes = parseIndexes(defs, problems);
    }

    public Map<ResourceLocation, VehicleWeaponIndex<?, ?>> getIndexes() {
        return indexes;
    }

    public Optional<VehicleWeaponIndex<?, ?>> getIndex(ResourceLocation id) {
        return Optional.ofNullable(indexes.get(id));
    }

    private static Map<ResourceLocation, VehicleWeaponIndex<?, ?>> parseIndexes(Map<ResourceLocation, JsonObject> map,
                                                                                List<String> problems) {
        var registry = ModRegistries.VEHICLE_WEAPON_TYPE;
        ImmutableMap.Builder<ResourceLocation, VehicleWeaponIndex<?, ?>> builder = ImmutableMap.builder();
        for (var entry : map.entrySet()) {
            ResourceLocation weaponId = entry.getKey();
            try {
                JsonObject weaponData = entry.getValue();
                String rawType = GsonHelper.getAsString(weaponData, "type");
                if (!rawType.contains(":")) {
                    rawType = CombinedAssault.MOD_ID + ":" + rawType;
                }
                var typeId = ResourceLocation.tryParse(rawType);
                var weaponType = typeId == null ? null : registry.get(typeId);
                if (weaponType == null) {
                    problems.add("weapon " + weaponId + ": unknown type " + rawType);
                    continue;
                }
                var index = weaponType.parseAndLoad(weaponId, weaponData);
                if (index == null) {
                    problems.add("weapon " + weaponId + ": type " + typeId + " refused it");
                    continue;
                }
                if (index.data().getWeaponId() == null) {
                    index.data().setWeaponId(weaponId);
                }
                index.data().loaded();
                builder.put(weaponId, index);
            } catch (Exception e) {
                problems.add("weapon " + weaponId + ": " + e);
            }
        }
        return builder.build();
    }

}
