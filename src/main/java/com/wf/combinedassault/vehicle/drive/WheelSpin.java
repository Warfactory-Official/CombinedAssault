package com.wf.combinedassault.vehicle.drive;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.dynamics.PhysicsEngine;

/**
 * Wheeled {@code attributes.wheelspin}. Absent => drive never limited by grip, handbrake = legacy 2 %/tick drag.
 * Budget = {@code drive_grip * G * share}; demand above it => spinning, delivered = {@code kinetic_ratio * budget}
 * until demand {@code <= kinetic_ratio * budget}.
 */
public final class WheelSpin {
    @SerializedName("drive_grip")
    public float driveGrip = (float) PhysicsEngine.TYRE_GRIP;
    @SerializedName("kinetic_ratio")
    public float kineticRatio = 0.7f;
    /** Handbrake deceleration in G per locked-wheel share; never reverses the vehicle. */
    @SerializedName("handbrake_hold")
    public float handbrakeHold = (float) PhysicsEngine.TYRE_GRIP;

    public void validate() {
        if (!(handbrakeHold > 0) || !(driveGrip > 0) || !(kineticRatio > 0 && kineticRatio <= 1)) {
            throw new IllegalArgumentException("wheelspin: drive_grip, handbrake_hold > 0, 0 < kinetic_ratio <= 1 required");
        }
    }

    /** Spinning next tick; accelerations in blocks/tick^2. */
    public boolean spins(boolean spinning, double demand, double budget) {
        return spinning ? demand > kineticRatio * budget : demand > budget;
    }

    /** Acceleration the tyres pass to the ground. */
    public double delivered(boolean spinning, double demand, double budget) {
        return spinning ? Math.min(demand, kineticRatio * budget) : demand;
    }
}
