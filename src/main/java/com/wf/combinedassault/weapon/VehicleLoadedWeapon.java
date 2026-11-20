package com.wf.combinedassault.weapon;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** cannon, rocket, grenade, aerial_bomb, decoy_flare: one loaded round per shot; type id = role. */
public class VehicleLoadedWeapon extends AbstractVehicleWeapon<BaseVehicleWeaponData> {

    private final ResourceLocation type;

    public VehicleLoadedWeapon(ResourceLocation type, AbstractVehicle vehicle, WeaponUnit unit, int index,
                               BaseVehicleWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
        this.type = type;
    }

    public boolean isType(ResourceLocation type) {
        return this.type.equals(type);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        return shootLoaded(aimContexts);
    }

}
