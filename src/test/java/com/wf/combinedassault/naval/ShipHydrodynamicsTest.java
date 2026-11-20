package com.wf.combinedassault.naval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipHydrodynamicsTest {
    private static ShipHydrodynamics.Params params(boolean sub) {
        ShipHydrodynamics.Params p = new ShipHydrodynamics.Params();
        p.waterline = 3;
        p.draft = 6;
        p.submarine = sub;
        p.maxDepth = 40;
        p.crushDepth = 60;
        p.implodeDepth = 90;
        p.floodDraft = 3;
        return p;
    }

    private static ShipHydrodynamics.Environment sea() {
        ShipHydrodynamics.Environment e = new ShipHydrodynamics.Environment();
        e.afloat = true;
        e.surfaceY = 64;
        e.waterDepth = 200;
        e.keelClearance = 190;
        return e;
    }

    private static void run(ShipHydrodynamics m, ShipHydrodynamics.Params p, ShipHydrodynamics.Pose pose,
                            ShipHydrodynamics.Controls c, int ticks) {
        for (int i = 0; i < ticks; i++) {
            m.step(p, pose, c, sea(), 1000, 1000, 100, 10, d -> d);
            pose.y += pose.vy;
        }
    }

    private static ShipHydrodynamics.Pose level(ShipHydrodynamics.Params p) {
        ShipHydrodynamics.Pose pose = new ShipHydrodynamics.Pose();
        pose.y = 64 - p.waterline;
        return pose;
    }

    @Test
    void aFloodedHullSettlesThenSinksListingToTheFloodedSide() {
        ShipHydrodynamics.Params p = params(false);
        ShipHydrodynamics m = new ShipHydrodynamics();
        ShipHydrodynamics.Pose pose = level(p);
        ShipHydrodynamics.Controls c = new ShipHydrodynamics.Controls();
        c.flood = 0.3;
        c.list = 1;
        run(m, p, pose, c, 400);
        double settled = 64 - p.waterline - pose.y;
        assertEquals(p.floodDraft * 0.5, settled, 0.3);
        assertTrue(pose.roll > 2, "list to starboard " + pose.roll);
        c.flood = 0.7;
        run(m, p, pose, c, 1200);
        assertTrue(64 - p.waterline - pose.y > 50, "sinks " + (64 - p.waterline - pose.y));
    }

    @Test
    void aHolesBallastTankLetsTheSubmarineSinkPastItsDepthLimit() {
        ShipHydrodynamics.Params p = params(true);
        ShipHydrodynamics m = new ShipHydrodynamics();
        ShipHydrodynamics.Pose pose = level(p);
        ShipHydrodynamics.Controls c = new ShipHydrodynamics.Controls();
        c.ballast = 0.2;
        run(m, p, pose, c, 3000);
        assertTrue(m.depthSetting > p.maxDepth, "setting " + m.depthSetting);
        c.ballast = 1.0;
        m.depthSetting = 20;
        c.rise = true;
        run(m, p, pose, c, 10);
        assertTrue(m.depthSetting < 20);
    }

    @Test
    void crushBandRampsThenImplodes() {
        ShipHydrodynamics.Params p = params(true);
        ShipHydrodynamics m = new ShipHydrodynamics();
        m.depth = 59;
        assertFalse(m.crushed(p));
        m.depth = 75;
        assertTrue(m.crushed(p));
        assertEquals(0.5, m.crushFraction(p), 1e-9);
        assertFalse(m.imploded(p));
        m.depth = 91;
        assertTrue(m.imploded(p));
        assertFalse(m.imploded(params(false)));
    }
}
