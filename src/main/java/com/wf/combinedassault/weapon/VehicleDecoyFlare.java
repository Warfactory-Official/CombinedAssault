package com.wf.combinedassault.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;

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
