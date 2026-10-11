package com.wf.combinedassault.vehicle.pojo;

/**
 * Radar behaviour on a shared sensor network.
 *
 * <p>Two independent axes, not a boolean: emitting detects at range^4, gets detected at range^2 (radiating =
 * always the louder party); receiving costs/announces nothing.
 *
 * <p>Only consulted with a sensor-network integration present; absent integration => every role acts as {@link #NODE}.
 */
public enum ReconRole {

    NODE,
    /** Radiates nothing; reads whole network incl. orbital relay. Pairs with camouflage rather than fighting it. */
    RECEIVER,
    BOTH;

    public boolean emits() {
        return this != RECEIVER;
    }

    public boolean receives() {
        return this != NODE;
    }

}
