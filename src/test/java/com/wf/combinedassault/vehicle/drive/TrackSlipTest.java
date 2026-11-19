package com.wf.combinedassault.vehicle.drive;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackSlipTest {
    private static final double STATIC = 0.02;
    private static final TrackSlip SLIP = new Gson().fromJson("{\"drive_grip\":0.8,\"kinetic_ratio\":0.6}", TrackSlip.class);

    @Test
    void parsesAndValidates() {
        SLIP.validate();
        assertEquals(0.8f, SLIP.driveGrip);
        assertEquals(0.6f, SLIP.kineticRatio);
        assertThrows(IllegalArgumentException.class, () -> new Gson().fromJson("{\"drive_grip\":0.8,\"kinetic_ratio\":1}", TrackSlip.class).validate());
        assertThrows(IllegalArgumentException.class, () -> new Gson().fromJson("{\"kinetic_ratio\":0.5}", TrackSlip.class).validate());
    }

    @Test
    void capBindsOnlyAboveBudget() {
        for (double demand : new double[]{0, 0.005, STATIC * 0.999, STATIC}) {
            boolean slipping = SLIP.slipping(false, demand, STATIC);
            assertFalse(slipping);
            assertEquals(demand, SLIP.delivered(demand, STATIC, 0, slipping));
            assertEquals(0, TrackSlip.slip(demand, demand));
        }
        double demand = STATIC * 1.5;
        boolean slipping = SLIP.slipping(false, demand, STATIC);
        assertTrue(slipping);
        double delivered = SLIP.delivered(demand, STATIC, 0, slipping);
        assertEquals(STATIC * 0.6, delivered, 1e-7);
        assertEquals(1 - STATIC * 0.6 / demand, TrackSlip.slip(demand, delivered), 1e-6);
    }

    @Test
    void hysteresis() {
        boolean slipping = SLIP.slipping(false, STATIC * 1.1, STATIC);
        assertTrue(slipping);
        slipping = SLIP.slipping(slipping, STATIC * 0.95, STATIC);
        assertTrue(slipping, "between exit and static: stays kinetic");
        assertEquals(STATIC * 0.6, SLIP.delivered(STATIC * 0.95, STATIC, 0, slipping), 1e-7);
        slipping = SLIP.slipping(slipping, STATIC * 0.85, STATIC);
        assertFalse(slipping);
        assertFalse(SLIP.slipping(false, STATIC * 0.95, STATIC), "never entered below static");
    }

    @Test
    void bellyDriveStaysOutsideTheCap() {
        double belly = 0.004;
        assertEquals(STATIC * 0.6 + belly, SLIP.delivered(1, STATIC, belly, true), 1e-7);
        assertEquals(STATIC + belly, SLIP.delivered(1, STATIC, belly, false), 1e-7);
        assertEquals(0.5 * STATIC, SLIP.delivered(0.5 * STATIC, STATIC, belly, false));
    }

    @Test
    void frictionCircleOnlyWhileSlipping() {
        assertEquals(1, SLIP.turnScale(false));
        assertEquals(0.8, SLIP.turnScale(true), 1e-6);
    }
}
