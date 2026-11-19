package com.wf.combinedassault.vehicle.drive;

import com.google.gson.annotations.SerializedName;

/**
 * Tracked {@code attributes.track_slip}. Budgets in blocks/tick per tick: static = {@code drive_grip * G * share};
 * kinetic = {@code kinetic_ratio * static}. Belly-borne drive adds outside both.
 */
public final class TrackSlip {
    /** Slipping ends only below this fraction of the static budget; share jitter at the boundary => no chatter. */
    public static final double EXIT = 0.9;

    /** Friction coefficient of the tracks, drive direction. */
    @SerializedName("drive_grip")
    public float driveGrip;
    @SerializedName("kinetic_ratio")
    public float kineticRatio;

    public void validate() {
        if (!(driveGrip > 0) || !(kineticRatio > 0) || !(kineticRatio < 1)) {
            throw new IllegalArgumentException("track_slip: needs drive_grip > 0, 0 < kinetic_ratio < 1");
        }
    }

    /** Slipping next tick: demand > static => true; demand < {@link #EXIT} x static => false; between => {@code was}. */
    public boolean slipping(boolean was, double demand, double staticBudget) {
        if (demand > staticBudget) {
            return true;
        }
        return was && demand >= EXIT * staticBudget;
    }

    /** Drive actually delivered (>= 0). */
    public double delivered(double demand, double staticBudget, double belly, boolean slipping) {
        return Math.min(demand, (slipping ? kineticRatio * staticBudget : staticBudget) + belly);
    }

    /** Lost fraction of {@code demand}, 0..1. */
    public static float slip(double demand, double delivered) {
        return demand > 0 ? (float) Math.min(1, Math.max(0, 1 - delivered / demand)) : 0;
    }

    /** Friction circle, only while slipping: the kinetic drive leaves {@code sqrt(1 - r^2)} of grip for yaw. */
    public double turnScale(boolean slipping) {
        return slipping ? Math.sqrt(1 - kineticRatio * kineticRatio) : 1;
    }
}
