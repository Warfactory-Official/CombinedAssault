package com.wf.combinedassault.uav.fpv.payload;

/** Hardpoint reacts to this, not a hard-wired kamikaze path; a payload that falls away needs no drone-side change. */
public enum FpvPayloadResult {

    NOTHING,

    /** Payload gone, drone flies on. */
    SPENT,

    DETONATED

}
