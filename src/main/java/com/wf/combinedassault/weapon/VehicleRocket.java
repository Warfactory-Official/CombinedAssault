package com.wf.combinedassault.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.weapon.data.VehicleRocketWeaponData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;

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
