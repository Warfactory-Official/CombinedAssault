package com.wf.combinedassault.aircraft.client;

import net.minecraft.util.Mth;

/**
 * Binding sources {@code stick_pitch} (+ = forward), {@code stick_roll} (+ = right), {@code pedals} (+ = right pedal
 * forward), -1..1; {@code throttle_lever}, {@code collective} 0..1.
 */
final class AircraftControls {
    /** Seconds from centre to full deflection. */
    private static final float TRAVEL = 0.15f;
    private float stickPitch;
    private float stickRoll;
    private float pedals;
    private float throttle;
    private float collective;

    void update(float pitch, float roll, float yaw, float throttleTarget, float collectiveTarget, float deltaTime) {
        float step = Math.max(0f, deltaTime) / TRAVEL;
        stickPitch = approach(stickPitch, Mth.clamp(pitch, -1f, 1f), step);
        stickRoll = approach(stickRoll, Mth.clamp(roll, -1f, 1f), step);
        pedals = approach(pedals, Mth.clamp(yaw, -1f, 1f), step);
        throttle = approach(throttle, Mth.clamp(throttleTarget, 0f, 1f), step);
        collective = approach(collective, Mth.clamp(collectiveTarget, 0f, 1f), step);
    }

    /** NaN: not a control source. */
    float value(String source) {
        return switch (source) {
            case "stick_pitch" -> stickPitch;
            case "stick_roll" -> stickRoll;
            case "pedals" -> pedals;
            case "throttle_lever" -> throttle;
            case "collective" -> collective;
            default -> Float.NaN;
        };
    }

    private static float approach(float value, float target, float step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }
}
