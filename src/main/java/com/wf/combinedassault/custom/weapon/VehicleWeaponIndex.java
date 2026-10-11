package com.wf.combinedassault.custom.weapon;

import net.minecraft.resources.ResourceLocation;
import com.wf.combinedassault.custom.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;

public record VehicleWeaponIndex<T extends AbstractVehicleWeapon<D>, D extends BaseVehicleWeaponData>(
        ResourceLocation id,
        VehicleWeaponType<T, D> type,
        D data
) {
    public T create(AbstractVehicle vehicle, WeaponUnit unit, int index, String serializeId) {
        return type.create(vehicle, unit, index, data, serializeId);
    }
}
