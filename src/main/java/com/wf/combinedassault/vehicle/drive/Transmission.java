package com.wf.combinedassault.vehicle.drive;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

/**
 * Wheeled {@code attributes.transmission}. Gear: {@link #REVERSE}, {@link #NEUTRAL}, 1..{@link #forwardGears()}.
 * Gear g top speed = {@code max_speed_forward * gears[last] / gears[g-1]}, force = {@code forward_force * gears[g-1] / gears[0]};
 * reverse = {@code max_speed_backward}, {@code backward_force}. rpm = idle + (max - idle) * speed / top speed.
 */
public final class Transmission {
    public static final int REVERSE = -1;
    public static final int NEUTRAL = 0;
    /** rpm fraction of {@code max_rpm}: automatic upshift above, downshift when the lower gear would sit below. */
    private static final float UPSHIFT = 0.9f;
    private static final float DOWNSHIFT = 0.8f;

    public enum Shift {
        @SerializedName("automatic") AUTOMATIC,
        @SerializedName("manual") MANUAL
    }

    @SerializedName("shift")
    public Shift shift = Shift.AUTOMATIC;
    /** Forward ratios, first gear first, strictly descending. */
    @SerializedName("gears")
    public float[] gears;
    @SerializedName("idle_rpm")
    public float idleRpm = 700;
    @SerializedName("max_rpm")
    public float maxRpm = 3400;
    /** Clutch open (no drive) per shift. */
    @SerializedName("shift_ticks")
    public int shiftTicks = 6;
    /** Lever pose per position R, N, 1..n: {@code [x, z]} degrees ({@code gear_lever_x} / {@code gear_lever_z} binding sources). */
    @Nullable
    @SerializedName("lever")
    public float[][] lever;

    public void validate() {
        if (gears == null || gears.length == 0) {
            throw new IllegalArgumentException("transmission: gears empty");
        }
        for (int i = 0; i < gears.length; i++) {
            if (!(gears[i] > 0) || i > 0 && !(gears[i] < gears[i - 1])) {
                throw new IllegalArgumentException("transmission: gears must be positive and strictly descending");
            }
        }
        if (!(idleRpm > 0) || !(maxRpm > idleRpm) || shiftTicks < 0) {
            throw new IllegalArgumentException("transmission: needs 0 < idle_rpm < max_rpm, shift_ticks >= 0");
        }
        if (lever != null) {
            if (lever.length != gears.length + 2) {
                throw new IllegalArgumentException("transmission: lever needs " + (gears.length + 2) + " positions (R, N, 1.."
                        + gears.length + ")");
            }
            for (float[] position : lever) {
                if (position.length != 2) {
                    throw new IllegalArgumentException("transmission: lever position is [x, z]");
                }
            }
        }
    }

    public int forwardGears() {
        return gears.length;
    }

    /** Blocks/tick at {@code max_rpm}; gear >= 1. */
    public float topSpeed(int gear, float maxSpeedForward) {
        return maxSpeedForward * gears[gears.length - 1] / gears[gear - 1];
    }

    /** Drive force multiplier of {@code forward_force}; gear >= 1. */
    public float forceScale(int gear) {
        return gears[gear - 1] / gears[0];
    }

    /** Wheel-locked rpm at {@code speed} (blocks/tick, >= 0) under {@code top}. */
    public float rpm(float speed, float top) {
        return idleRpm + (maxRpm - idleRpm) * Math.min(speed / top, 1);
    }

    /** Automatic choice from {@code gear} (>= 1) at forward {@code speed}. */
    public int automatic(int gear, float speed, float maxSpeedForward) {
        if (gear < gears.length && speed > UPSHIFT * topSpeed(gear, maxSpeedForward)) {
            return gear + 1;
        }
        if (gear > 1 && speed < DOWNSHIFT * topSpeed(gear - 1, maxSpeedForward)) {
            return gear - 1;
        }
        return gear;
    }

    public static String label(int gear) {
        return gear == REVERSE ? "R" : gear == NEUTRAL ? "N" : Integer.toString(gear);
    }
}
