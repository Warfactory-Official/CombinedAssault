package com.wf.combinedassault.vehicle.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.custom.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class VehicleWeaponAgent extends AbstractVehicleWeapon<BaseVehicleWeaponData> {

    public VehicleWeaponAgent(AbstractVehicle vehicle, WeaponUnit weaponUnit, int index) {
        super(vehicle, weaponUnit, index, new BaseVehicleWeaponData(), null);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        return weaponUnit.getCurrentWeapon().get().shoot(aimContexts, shooter);
    }

}
