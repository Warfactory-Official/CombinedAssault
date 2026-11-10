package com.wf.combinedassault.aircraft.client;

import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.ballistics.component.VehicleRotors;
import com.wf.combinedassault.render.animation.context.VehicleContext;
import net.minecraft.util.Mth;

public class RotaryWingVehicleContext extends VehicleContext<RotaryWingVehicle> {

    private final AircraftControls controls = new AircraftControls();

    public RotaryWingVehicleContext(RotaryWingVehicle vehicle) {
        super(vehicle);
    }

    @Override
    public void tick() {
        super.tick();
        float yawRate = Mth.wrapDegrees(entity.getYRot() - entity.yRotO) / entity.yRotSpeedMax;
        controls.update(-getPitchInput(), getRollInput(), yawRate, 0f, getCollectivePitch() / 100f,
                (currentTimeMillis() - lastRenderTime()) / 1000f);
    }

    @Override
    public float getBindingValue(String source, Float param) {
        float control = controls.value(source);
        return Float.isNaN(control) ? super.getBindingValue(source, param) : control;
    }

    public float getCollectivePitch() {
        return Mth.lerp(partialTick, entity.collectivePitchO, entity.collectivePitch);
    }

    public float getPitchInput() {
        return Mth.lerp(partialTick, entity.pitchInputO, entity.pitchInput);
    }

    public float getRollInput() {
        return Mth.lerp(partialTick, entity.rollInputO, entity.rollInput);
    }

    /** 0 = main rotor broken. */
    public float getRotorLift() {
        return VehicleRotors.of(entity).lift();
    }

    /** 0 = tail rotor broken. */
    public float getRotorAntiTorque() {
        return VehicleRotors.of(entity).antiTorque();
    }

    public float getCargoRopeLength() {
        return entity.cargoRope == null ? 0 : entity.cargoRope.getLength();
    }

}
