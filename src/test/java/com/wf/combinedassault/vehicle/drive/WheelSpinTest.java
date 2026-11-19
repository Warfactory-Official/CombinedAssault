package com.wf.combinedassault.vehicle.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WheelSpinTest {
    private static WheelSpin spin() {
        WheelSpin w = new WheelSpin();
        w.driveGrip = 1;
        w.kineticRatio = 0.7f;
        w.validate();
        return w;
    }

    @Test
    void demandWithinBudgetPassesThrough() {
        WheelSpin w = spin();
        assertFalse(w.spins(false, 0.9, 1));
        assertEquals(0.9, w.delivered(false, 0.9, 1));
    }

    @Test
    void demandAboveBudgetSpinsAtKineticBudget() {
        WheelSpin w = spin();
        assertTrue(w.spins(false, 1.1, 1));
        assertEquals(0.7, w.delivered(true, 1.1, 1), 1e-6);
    }

    @Test
    void spinHoldsUntilDemandDropsBelowKineticBudget() {
        WheelSpin w = spin();
        assertTrue(w.spins(true, 0.9, 1));
        assertTrue(w.spins(true, 0.71, 1));
        assertFalse(w.spins(true, 0.69, 1));
        assertEquals(0.69, w.delivered(true, 0.69, 1), 1e-6);
    }

    @Test
    void zeroBudgetDeliversNothingWithoutDivision() {
        WheelSpin w = spin();
        assertTrue(w.spins(false, 0.1, 0));
        assertEquals(0, w.delivered(true, 0.1, 0));
        assertFalse(w.spins(false, 0, 0));
    }

    @Test
    void invalidConfigRejected() {
        WheelSpin w = new WheelSpin();
        w.kineticRatio = 1.5f;
        assertThrows(IllegalArgumentException.class, w::validate);
    }

    @Test
    void parsesHold() {
        WheelSpin w = new com.google.gson.Gson().fromJson("{\"handbrake_hold\":0.9}", WheelSpin.class);
        w.validate();
        assertEquals(0.9f, w.handbrakeHold);
        assertThrows(IllegalArgumentException.class, () -> {
            WheelSpin bad = new WheelSpin();
            bad.handbrakeHold = 0;
            bad.validate();
        });
    }
}
