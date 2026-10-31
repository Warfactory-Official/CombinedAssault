package com.wf.combinedassault.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.weapon.data.VehicleGrenadeWeaponData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;

import java.util.List;

public class VehicleGrenade extends AbstractVehicleWeapon<VehicleGrenadeWeaponData> {

    public VehicleGrenade(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleGrenadeWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        return check(aimContexts, shooter) && shootLoaded(aimContexts);
    }

}
