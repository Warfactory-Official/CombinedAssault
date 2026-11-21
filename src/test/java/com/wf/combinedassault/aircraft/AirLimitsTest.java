package com.wf.combinedassault.aircraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AirLimitsTest {
    @Test
    void finiteCeilingTapersThenFlamesOut() {
        assertEquals(1f, AirLimits.thrust(64, 256, 281.6), 0);
        assertEquals(AirLimits.THRUST_FLOOR, AirLimits.thrust(256, 256, 281.6), 1e-6);
        assertEquals(0f, AirLimits.thrust(282, 256, 281.6), 0);
        assertEquals(1.0, AirLimits.density(63, 256, 0.5), 0);
        assertEquals(0.0, AirLimits.density(256, 256, 0.5), 0);
    }

    @Test
    void infiniteCeilingNeverTapersOrFlamesOut() {
        double inf = Double.POSITIVE_INFINITY;
        for (double y : new double[] {64, 300, 5000, 1.0e6}) {
            assertEquals(1f, AirLimits.thrust(y, inf, inf), 0, "thrust at " + y);
            assertEquals(1.0, AirLimits.density(y, inf, 0.5), 0, "density at " + y);
            assertEquals(1.0, AirLimits.density(y, inf, 0.2), 0, "density at " + y);
        }
    }
}
