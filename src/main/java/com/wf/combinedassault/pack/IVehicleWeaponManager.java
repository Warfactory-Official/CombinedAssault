package com.wf.combinedassault.pack;

import com.wf.combinedassault.pack.weapon.VehicleWeaponIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;

public interface IVehicleWeaponManager {
    Map<ResourceLocation, VehicleWeaponIndex<?, ?>> getIndexes();

    Optional<VehicleWeaponIndex<?, ?>> getIndex(ResourceLocation id);
}
