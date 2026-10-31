package com.wf.combinedassault.weapon.client;

import com.wf.wflib.recoil.api.RecoilRing;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoilRingTest {
    /** 12.7x99 ball: 42 g at 890 m/s. */
    private static final float M2 = 0.042F * 890;
    private static final float[] OUT = new float[2];

    /** Summed step deltas {@code [yaw, pitch]} over {@code [start, end]} s at {@code dt}. */
    private static float[] run(RecoilRing ring, double start, double end, double dt) {
        float[] sum = new float[2];
        for (int i = 0; start + i * dt <= end + 1e-9; i++) {
            double t = start + i * dt;
            ring.step(StationRecoil.PROFILE, t, OUT);
            sum[0] += OUT[0];
            sum[1] += OUT[1];
        }
        return sum;
    }

    @Test
    void manualShotClimbsThenRecovers() {
        RecoilRing ring = new RecoilRing();
        StationRecoil.shot(ring, 0, M2, true, new SplittableRandom(1));
        float peak = StationRecoil.manualPeak(M2);
        float[] atRise = run(ring, 0, StationRecoil.RISE, 0.01);
        assertTrue(-atRise[1] >= 0.7F * peak - 1e-4 && -atRise[1] <= peak + 1e-4, "climb " + atRise[1]);
        float[] rest = run(ring, StationRecoil.RISE + 0.05, 5, 0.05);
        assertEquals(0, atRise[1] + rest[1], 1e-3);
        assertEquals(0, atRise[0] + rest[0], 1e-3);
    }

    @Test
    void sustainedManualFireWalksUp() {
        RecoilRing ring = new RecoilRing();
        SplittableRandom random = new SplittableRandom(2);
        float climb = 0;
        for (int i = 0; i < 9; i++) {
            StationRecoil.shot(ring, i * 0.11, M2, true, random);
            for (int s = 1; s <= 2; s++) {
                ring.step(StationRecoil.PROFILE, i * 0.11 + s * 0.055, OUT);
                climb += OUT[1];
            }
        }
        assertTrue(-climb > 0.1 && -climb < 0.5, "climb " + climb);
    }

    @Test
    void remoteShotNeverMovesTheAim() {
        RecoilRing ring = new RecoilRing();
        StationRecoil.shot(ring, 0, M2, false, new SplittableRandom(3));
        float[] sum = run(ring, 0, 1, 0.01);
        assertEquals(0, sum[0]);
        assertEquals(0, sum[1]);
        float a = 0.35F * 28, b = 28 * (float) Math.sqrt(1 - 0.35F * 0.35F);
        double tPeak = Math.atan2(b, a) / b;
        assertEquals(StationRecoil.jolt(M2), ring.spring(tPeak, RecoilRing.PITCH, 28, 0.35F), 1e-4);
        assertEquals(0, ring.spring(2, RecoilRing.PITCH, 28, 0.35F), 1e-3);
    }

    @Test
    void magnitudesFollowMomentum() {
        assertEquals(0.15F, StationRecoil.manualPeak(M2), 0.01);
        assertEquals(StationRecoil.MANUAL_MAX, StationRecoil.manualPeak(8.5F * 1700));
        assertEquals(0, StationRecoil.manualPeak(0));
        assertTrue(StationRecoil.jolt(8) < StationRecoil.jolt(M2));
        assertEquals(StationRecoil.JOLT_MAX, StationRecoil.jolt(8.5F * 1700));
    }
}
