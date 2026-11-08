package com.wf.combinedassault.render.animation.context;

import com.wf.combinedassault.vehicle.entity.GroundVehicle;

/**
 * Binding sources {@code throttle_pedal}, {@code brake_pedal}, {@code handbrake} (0..1) and {@code gear_lever_x} /
 * {@code gear_lever_z} (degrees, transmission {@code lever}). Lever: H gate, fore/aft to N's x before crossing.
 */
final class DriverControls {
    /** Seconds for a pedal / handbrake to travel fully. */
    private static final float PEDAL_TRAVEL = 0.12f;
    /** Degrees per second along either gate axis. */
    private static final float LEVER_RATE = 240f;
    private float throttlePedal;
    private float brakePedal;
    private float handbrake;
    private float leverX = Float.NaN;
    private float leverZ;

    void update(GroundVehicle vehicle, float deltaTime) {
        int pedals = vehicle.getPedals();
        float step = deltaTime / PEDAL_TRAVEL;
        throttlePedal = approach(throttlePedal, (pedals & GroundVehicle.THROTTLE) != 0 ? 1 : 0, step);
        brakePedal = approach(brakePedal, (pedals & GroundVehicle.BRAKE) != 0 ? 1 : 0, step);
        handbrake = approach(handbrake, (pedals & GroundVehicle.HANDBRAKE) != 0 ? 1 : 0, step);
        float[][] lever = vehicle.transmission == null ? null : vehicle.transmission.lever;
        if (lever == null) {
            return;
        }
        float[] target = lever[vehicle.getGear() + 1];
        if (Float.isNaN(leverX)) {
            leverX = target[0];
            leverZ = target[1];
            return;
        }
        float travel = LEVER_RATE * deltaTime;
        if (leverZ != target[1]) {
            float gate = lever[1][0];
            if (leverX != gate) {
                leverX = approach(leverX, gate, travel);
            } else {
                leverZ = approach(leverZ, target[1], travel);
            }
        } else {
            leverX = approach(leverX, target[0], travel);
        }
    }

    /** NaN: not a control source. */
    float value(String source) {
        return switch (source) {
            case "throttle_pedal" -> throttlePedal;
            case "brake_pedal" -> brakePedal;
            case "handbrake" -> handbrake;
            case "gear_lever_x" -> Float.isNaN(leverX) ? 0 : leverX;
            case "gear_lever_z" -> leverZ;
            default -> Float.NaN;
        };
    }

    private static float approach(float value, float target, float step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }
}
