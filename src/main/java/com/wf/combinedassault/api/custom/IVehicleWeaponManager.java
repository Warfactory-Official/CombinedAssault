package com.wf.combinedassault.api.custom;

import net.minecraft.resources.ResourceLocation;
import com.wf.combinedassault.custom.weapon.VehicleWeaponIndex;

import java.util.Map;
import java.util.Optional;

public interface IVehicleWeaponManager {
    Map<ResourceLocation, VehicleWeaponIndex<?, ?>> getIndexes();

    Optional<VehicleWeaponIndex<?, ?>> getIndex(ResourceLocation id);
}
