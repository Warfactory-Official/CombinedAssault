package com.wf.combinedassault.ballistics;

/** What the armour did to a shot, and the vocabulary the hit readout speaks. */
public enum HitOutcome {

    /** Skated off. Nothing behind the plate is touched. */
    RICOCHET,
    /** Stopped by the plate. A little energy arrives as spall, nothing more. */
    ABSORBED,
    /** Through, but only just, and much of the shell's energy went into getting there. */
    PARTIAL,
    /** Through cleanly. */
    PENETRATION,
    /** Through as if the plate were not there. */
    OVERMATCH;

    public boolean isThrough() {
        return this == PARTIAL || this == PENETRATION || this == OVERMATCH;
    }

    public String translationKey() {
        return "message.vehicle.ballistics." + name().toLowerCase(java.util.Locale.ROOT);
    }

}
