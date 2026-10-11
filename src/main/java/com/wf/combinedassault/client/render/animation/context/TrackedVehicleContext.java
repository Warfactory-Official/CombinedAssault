package com.wf.combinedassault.client.render.animation.context;

import com.wf.combinedassault.entity.vehicle.TrackedVehicle;
import com.wf.combinedassault.vehicle.part.TrackUnit;

import java.util.OptionalDouble;

public class TrackedVehicleContext extends VehicleContext<TrackedVehicle> {

    private float leftTrackDisplacement;
    private float rightTrackDisplacement;

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
        this.advanceTrackProgress();
    }

    public void advanceTrackProgress() {
        float deltaTime = (this.currentTimeMillis() - this.lastRenderTime()) / 1000f;
        float forwardSpeed = this.getForwardSpeed();
        float turnSpeed = this.getTurnSpeed();
        float linearSpeed = forwardSpeed * 20;
        firstTrackLateralOffset(true).ifPresent(offset ->
                leftTrackDisplacement += (float) (linearSpeed + turnSpeed * offset) * deltaTime);
        firstTrackLateralOffset(false).ifPresent(offset ->
                rightTrackDisplacement += (float) (linearSpeed + turnSpeed * offset) * deltaTime);
    }

    public float getLeftWheelDegrees(float leftDriveRadius) {
        return wheelRotation(leftTrackDisplacement, leftDriveRadius);
    }

    public float getRightWheelDegrees(float rightDriveRadius) {
        return wheelRotation(rightTrackDisplacement, rightDriveRadius);
    }

    private OptionalDouble firstTrackLateralOffset(boolean left) {
        for (var partUnit : entity.getPartUnits()) {
            if (partUnit instanceof TrackUnit trackUnit) {
                OptionalDouble offset = left
                        ? trackUnit.getFirstLeftTrackLateralOffset()
                        : trackUnit.getFirstRightTrackLateralOffset();
                if (offset.isPresent()) {
                    return offset;
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static float wheelRotation(float displacement, float radius) {
        if (radius == 0) {
            return 0;
        }
        return displacement / (2f * (float) Math.PI * radius) * 360f;
    }

    @Override
    public float getBindingValue(String source, Float param) {
        float paramValue = param != null ? param : 0f;
        return switch (source) {
            case "left_wheel_rotation" -> getLeftWheelDegrees(paramValue);
            case "right_wheel_rotation" -> getRightWheelDegrees(paramValue);
            default -> super.getBindingValue(source, param);
        };
    }

}
