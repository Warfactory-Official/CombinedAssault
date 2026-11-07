package com.wf.combinedassault.pack.weapon;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.resources.ResourceLocation;

public record VehicleWeaponIndex<T extends AbstractVehicleWeapon<D>, D extends BaseVehicleWeaponData>(
        ResourceLocation id,
        VehicleWeaponType<T, D> type,
        D data
) {
    public T create(AbstractVehicle vehicle, WeaponUnit unit, int index, String serializeId) {
        return type.create(vehicle, unit, index, data, serializeId);
    }
}
