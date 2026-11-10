package com.wf.combinedassault.aircraft.client;

import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.render.animation.context.VehicleContext;
import net.minecraft.util.Mth;

public class FixedWingVehicleContext extends VehicleContext<FixedWingVehicle> {

    private final AircraftControls controls = new AircraftControls();

    public FixedWingVehicleContext(FixedWingVehicle vehicle) {
        super(vehicle);
    }

    @Override
    public void tick() {
        super.tick();
        float full = 100f * Math.max(1f, entity.thrustK);
        controls.update(getPitchInput(), getRollInput(), -getYawInput(), Math.min(getThrottleLevel(), full) / full, 0f,
                (currentTimeMillis() - lastRenderTime()) / 1000f);
    }

    @Override
    public float getBindingValue(String source, Float param) {
        float control = controls.value(source);
        return Float.isNaN(control) ? super.getBindingValue(source, param) : control;
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
