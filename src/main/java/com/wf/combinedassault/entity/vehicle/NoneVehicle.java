package com.wf.combinedassault.entity.vehicle;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.api.animation.IAnimationEntity;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.client.render.animation.context.VehicleContext;
import com.wf.combinedassault.client.resource.vehicle.SimpleVehicleDisplay;
import com.wf.combinedassault.client.resource.vehicle.VehicleDisplay;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class NoneVehicle extends AbstractVehicle implements IAnimationEntity<AbstractVehicle, VehicleContext<AbstractVehicle>> {

    private IAnimationInstance<VehicleContext<AbstractVehicle>> animationInstance;

    public NoneVehicle(EntityType<? extends AbstractVehicle> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Override
    public void shoot(int partUnitIndex, int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator) {
        if (partUnits.get(partUnitIndex) instanceof WeaponUnit weaponUnit) {
            weaponUnit.shoot(weaponIndex, aimContexts, operator);
        }
    }

    @Override
    protected Vec3 tickMove() {
        return Vec3.ZERO;
    }

    @Override
    public IAnimationInstance<VehicleContext<AbstractVehicle>> getAnimationInstance() {
        return animationInstance;
    }

    @Override
    public void initDisplayData(VehicleDisplay<?, ?> display) {
        super.initDisplayData(display);
        if (display instanceof SimpleVehicleDisplay simpleVehicleDisplay) {
            this.animationInstance = simpleVehicleDisplay.createAnimationInstance(this);
        }
    }

}
