package com.wf.combinedassault.vehicle.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.custom.weapon.data.VehicleAerialBombWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class VehicleAerialBomb extends AbstractVehicleWeapon<VehicleAerialBombWeaponData> {

    public VehicleAerialBomb(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleAerialBombWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        return shootLoaded(aimContexts);
    }

}
