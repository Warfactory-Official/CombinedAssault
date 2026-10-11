package com.wf.combinedassault.client.render.animation.context;

import net.minecraft.util.Mth;
import com.wf.combinedassault.ballistics.component.VehicleRotors;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;

public class RotaryWingVehicleContext extends VehicleContext<RotaryWingVehicle> {

    public RotaryWingVehicleContext(RotaryWingVehicle vehicle) {
        super(vehicle);
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
