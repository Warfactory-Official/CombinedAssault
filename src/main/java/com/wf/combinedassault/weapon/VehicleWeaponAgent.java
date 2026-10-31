package com.wf.combinedassault.weapon;

import net.minecraft.world.entity.LivingEntity;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;

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
