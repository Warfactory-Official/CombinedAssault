package com.wf.combinedassault.render.animation.context;

import com.wf.combinedassault.vehicle.entity.TrackedVehicle;

public class TrackedVehicleContext extends VehicleContext<TrackedVehicle> {
    private final DriverControls controls = new DriverControls();
    private final TrackWheels trackWheels = new TrackWheels();

    public TrackedVehicleContext(TrackedVehicle vehicle) {
        super(vehicle);
    }

    public float getForwardSpeed() {
        return entity.getForwardSpeed();
    }

    public float getTurnSpeed() {
        return entity.getTurnSpeed();
    }

    @Override
    public void tick() {
        super.tick();
        float deltaTime = this.frameSeconds();
        trackWheels.advance(entity, deltaTime);
        controls.update(entity, deltaTime);
    }

    @Override
    public float getBindingValue(String source, Float param) {
        float wheel = trackWheels.binding(source, param != null ? param : 0f);
        if (!Float.isNaN(wheel)) {
            return wheel;
        }
        float control = controls.value(source);
        return Float.isNaN(control) ? super.getBindingValue(source, param) : control;
    }

}
