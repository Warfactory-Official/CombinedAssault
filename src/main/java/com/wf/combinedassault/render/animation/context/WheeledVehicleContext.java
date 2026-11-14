package com.wf.combinedassault.render.animation.context;

import com.wf.combinedassault.vehicle.entity.WheeledVehicle;
import net.minecraft.util.Mth;

public class WheeledVehicleContext extends VehicleContext<WheeledVehicle> {

    /** {@code distance*360}, pre-circumference; {@link #getWheelDegrees} divides by {@code 2*pi*radius}. */
    private float wheelRotation = 0f;
    private static final float STEER_SMOOTH_SECONDS = 0.05f;
    private float steeringAngle = 0f;
    private final DriverControls controls = new DriverControls();
    /** Half-track belts. */
    private final TrackWheels trackWheels = new TrackWheels();

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
        float deltaTime = this.frameSeconds();
        this.advanceWheelRotation();
        this.updateSteeringAngle();
        trackWheels.advance(entity, deltaTime);
        controls.update(entity, deltaTime);
    }

    private void advanceWheelRotation() {
        float deltaTime = this.frameSeconds();
        float forwardSpeed = this.getForwardSpeed();

        float distance = forwardSpeed * deltaTime * 20;
        wheelRotation += distance * 360f;
    }

    private void updateSteeringAngle() {
        float target = -this.getTurnAngle() * 16f;
        steeringAngle += (target - steeringAngle) * (1 - (float) Math.exp(-this.frameSeconds() / STEER_SMOOTH_SECONDS));
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
            case "left_wheel_rotation", "right_wheel_rotation" -> trackWheels.binding(source, paramValue);
            default -> {
                float control = controls.value(source);
                yield Float.isNaN(control) ? super.getBindingValue(source, param) : control;
            }
        };
    }

}
