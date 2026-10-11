package com.wf.combinedassault.client.render.animation.context;

import net.minecraft.util.Mth;
import com.wf.combinedassault.entity.vehicle.WheeledVehicle;

public class WheeledVehicleContext extends VehicleContext<WheeledVehicle> {

    /** {@code distance*360}, pre-circumference; {@link #getWheelDegrees} divides by {@code 2*pi*radius}. */
    private float wheelRotation = 0f;
    private float steeringAngle = 0f;

    public WheeledVehicleContext(WheeledVehicle vehicle) {
        super(vehicle);
    }

    public float getForwardSpeed() {
        return entity.getForwardSpeed();
    }

    public float getTurnAngle() {
        return Mth.lerp(partialTick, entity.turnAngleO, entity.turnAngle);
    }

    @Override
    public void tick() {
        super.tick();
        this.advanceWheelRotation();
        this.updateSteeringAngle();
    }

    private void advanceWheelRotation() {
        float deltaTime = (this.currentTimeMillis() - this.lastRenderTime()) / 1000f;
        float forwardSpeed = this.getForwardSpeed();

        float distance = forwardSpeed * deltaTime * 20;
        wheelRotation += distance * 360f;
    }

    private void updateSteeringAngle() {
        steeringAngle = -this.getTurnAngle() * 16f;
    }

    /** {@code radius} in blocks. */
    public float getWheelDegrees(float radius) {
        float circumference = (float) (2 * Math.PI * radius);
        return (wheelRotation / circumference) % 360f;
    }

    public float getWheelRotation() {
        return wheelRotation;
    }

    public float getSteeringAngle() {
        return steeringAngle;
    }

    public float getSteeringAngle(float maxAngle) {
        return Math.max(-maxAngle, Math.min(maxAngle, steeringAngle));
    }

    @Override
    public float getBindingValue(String source, Float param) {
        float paramValue = param != null ? param : 0f;
        return switch (source) {
            case "wheel_rotation" -> getWheelDegrees(paramValue);
            case "steering_angle" -> getSteeringAngle();
            default -> super.getBindingValue(source, param);
        };
    }

}
