package com.wf.combinedassault.vehicle.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.custom.weapon.data.VehicleRocketWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class VehicleRocket extends AbstractVehicleWeapon<VehicleRocketWeaponData> {

    public VehicleRocket(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleRocketWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        return shootLoaded(aimContexts);
    }

}
