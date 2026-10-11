package com.wf.combinedassault.entity.vehicle.custom;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;

public class Quadcopter extends RotaryWingVehicle {

    public Quadcopter(EntityType<? extends AbstractVehicle> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

}
