package com.wf.combinedassault.fpv.payload;

/** Payload opts in per-trigger. */
public enum FpvTrigger {

    /** Struck a block or entity hard enough to matter. */
    IMPACT,

    /** Operator pressed the trigger key. */
    OPERATOR,

    /** Operator let the charge go: falls free, fuzed on impact. */
    RELEASE,

    /** Control link died with payload still armed. */
    LINK_LOST,

    /** Drone destroyed while carrying this payload. */
    DRONE_DESTROYED

}
