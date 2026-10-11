package com.wf.combinedassault.fpv.payload;

/** Payload opts in per-trigger: warhead answers {@link #IMPACT}, a future drop-charge only {@link #OPERATOR}. */
public enum FpvTrigger {

    /** Struck a block or entity hard enough to matter. */
    IMPACT,

    /** Operator pressed the trigger key. */
    OPERATOR,

    /** Control link died with payload still armed. */
    LINK_LOST,

    /** Drone destroyed while carrying this payload. */
    DRONE_DESTROYED

}
