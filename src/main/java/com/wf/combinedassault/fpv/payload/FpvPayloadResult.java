package com.wf.combinedassault.fpv.payload;

/** Hardpoint reacts to this, not a hard-wired kamikaze path; a payload that falls away needs no drone-side change. */
public enum FpvPayloadResult {

    NOTHING,

    /** Payload gone, drone flies on; the shape a future mortar drop will take. */
    SPENT,

    DETONATED

}
