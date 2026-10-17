package com.wf.combinedassault.multirotor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Quad X: FL cw, FR ccw, RL ccw, RR cw at (+-1, +-1). */
class RotorLayoutTest {
    private static final double[] X = {1, -1, 1, -1};
    private static final double[] Z = {1, 1, -1, -1};
    private static final int[] SPIN = {1, -1, -1, 1};

    @Test
    void fullQuadHoldsEveryRotor() {
        assertEquals(4, RotorLayout.solve(new double[]{1, 1, 1, 1}, X, Z, SPIN), 1e-6);
    }

    @Test
    void weakRotorCapsTheOthersToItsLevel() {
        assertEquals(2, RotorLayout.solve(new double[]{1, 0.5, 1, 1}, X, Z, SPIN), 1e-6);
        assertEquals(1.2, RotorLayout.solve(new double[]{0.9, 0.3, 1, 0.6}, X, Z, SPIN), 1e-6);
    }

    @Test
    void deadRotorLeavesNoBalancedThrust() {
        assertEquals(0, RotorLayout.solve(new double[]{1, 1, 0, 1}, X, Z, SPIN), 1e-6);
    }

    @Test
    void coaxialPairsSurviveALoss() {
        // X8-like: each arm cw over ccw; one prop lost => its arm partner and the opposite arm's pair throttle down.
        double[] x = {1, 1, -1, -1, 1, 1, -1, -1};
        double[] z = {1, 1, 1, 1, -1, -1, -1, -1};
        int[] spin = {1, -1, -1, 1, -1, 1, 1, -1};
        double[] e = {1, 1, 1, 1, 1, 1, 1, 1};
        e[0] = 0;
        double balanced = RotorLayout.solve(e, x, z, spin);
        assertEquals(true, balanced > 4, "X8 one prop out: " + balanced);
    }
}
