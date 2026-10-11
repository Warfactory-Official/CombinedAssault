package com.wf.combinedassault.vehicle.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.custom.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class VehicleDecoyFlare extends AbstractVehicleWeapon<BaseVehicleWeaponData> {

    public VehicleDecoyFlare(AbstractVehicle vehicle, WeaponUnit unit, int index, BaseVehicleWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        return shootLoaded(aimContexts);
    }

}
