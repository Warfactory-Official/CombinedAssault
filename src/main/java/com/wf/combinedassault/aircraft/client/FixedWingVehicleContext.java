package com.wf.combinedassault.aircraft.client;

import net.minecraft.util.Mth;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.render.animation.context.VehicleContext;

public class FixedWingVehicleContext extends VehicleContext<FixedWingVehicle> {

    public FixedWingVehicleContext(FixedWingVehicle vehicle) {
        super(vehicle);
    }

    public float getThrottleLevel() {
        return Mth.lerp(partialTick, entity.throttleLevelO, entity.throttleLevel);
    }

    public float getPitchInput() {
        return Mth.lerp(partialTick, entity.pitchInputO, entity.pitchInput);
    }

    public float getYawInput() {
        return Mth.lerp(partialTick, entity.yawInputO, entity.yawInput);
    }

    public float getRollInput() {
        return Mth.lerp(partialTick, entity.rollInputO, entity.rollInput);
    }

}
