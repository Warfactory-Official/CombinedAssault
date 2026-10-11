package com.wf.combinedassault.client.render.animation.context;

import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import net.minecraft.util.Mth;

public class ShipVehicleContext extends VehicleContext<ShipVehicle> {

    private double shaft;
    private double shaftO;

    public ShipVehicleContext(ShipVehicle vehicle) {
        super(vehicle);
    }

    @Override
    public void tick() {
        super.tick();
        shaftO = shaft;
        shaft += entity.hasPower() ? entity.throttle() : 0;
    }

    /** Shaft phase: sum of telegraph (-1..1) per tick; script x degrees per tick at full. */
    public float getShaft() {
        return (float) Mth.lerp(partialTick, shaftO, shaft);
    }

    public float getThrottle() {
        return entity.throttle();
    }

    public float getRudder() {
        return entity.rudder();
    }
}
