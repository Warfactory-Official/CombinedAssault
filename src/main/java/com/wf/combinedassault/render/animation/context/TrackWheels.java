package com.wf.combinedassault.render.animation.context;

import com.wf.combinedassault.dynamics.part.TrackUnit;
import com.wf.combinedassault.vehicle.entity.GroundVehicle;

import java.util.OptionalDouble;

/** {@code left_wheel_rotation} / {@code right_wheel_rotation}: belt travel of the first left / right track loop. */
final class TrackWheels {

    private float left;
    private float right;

    void advance(GroundVehicle vehicle, float deltaSeconds) {
        firstLateralOffset(vehicle, true).ifPresent(offset -> left += (float) vehicle.trackSpeed(offset) * deltaSeconds);
        firstLateralOffset(vehicle, false).ifPresent(offset -> right += (float) vehicle.trackSpeed(offset) * deltaSeconds);
    }

    /** NaN: not a track wheel source. */
    float binding(String source, float radius) {
        return switch (source) {
            case "left_wheel_rotation" -> degrees(left, radius);
            case "right_wheel_rotation" -> degrees(right, radius);
            default -> Float.NaN;
        };
    }

    private static OptionalDouble firstLateralOffset(GroundVehicle vehicle, boolean left) {
        for (var partUnit : vehicle.getPartUnits()) {
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

    private static float degrees(float displacement, float radius) {
        if (radius == 0) {
            return 0;
        }
        return displacement / (2f * (float) Math.PI * radius) * 360f;
    }
}
