package com.wf.combinedassault.recoil;

import java.util.random.RandomGenerator;

/**
 * Port of MineAssault {@code RecoilCamera} for one station. Divergence: per-shot curves fixed ({@link #RISE} /
 * {@link #SETTLE} keys) and scaled by round momentum, no ADS / posture factors; remote shots carry jolt weights only.
 * Degrees {@code [yaw, pitch]}, MC signs (climb = negative pitch); seconds.
 */
public final class RecoilRing {
    static final int SHOTS = 32;
    public static final int PITCH = 0, YAW = 1, ROLL = 2;

    static final float RISE = 0.05F, SETTLE = 0.18F;
    static final float RECOVERY_DELAY = 0.15F, RECOVERY_RATE = 5F;
    /** Manual peak climb {@code K sqrt(p)} deg, p = N s per shot event. */
    static final float MANUAL_K = 0.025F, MANUAL_MIN = 0.04F, MANUAL_MAX = 0.6F;
    /** Remote jolt {@code K log2(1 + p / P0)} deg. */
    static final float JOLT_K = 0.05F, JOLT_P0 = 10F, JOLT_MIN = 0.02F, JOLT_MAX = 0.4F;

    private final double[] shotAt = new double[SHOTS];
    /** Manual peak deg; 0 = remote. */
    private final float[] peak = new float[SHOTS];
    private final float[] uPitch = new float[SHOTS];
    private final float[] uYaw = new float[SHOTS];
    private final float[] weight = new float[SHOTS * 3];
    private final float[] applied = new float[SHOTS * 2];
    private final float[] acc = new float[2];
    private final float[] tmp = new float[2];
    private int count;
    private double lastShot;
    private double prev = Double.NaN;

    public void reset() {
        count = 0;
        acc[0] = acc[1] = 0;
        prev = Double.NaN;
    }

    public int shots() {
        return count;
    }

    public static float manualPeak(float impulse) {
        return impulse <= 0 ? 0 : Math.clamp(MANUAL_K * (float) Math.sqrt(impulse), MANUAL_MIN, MANUAL_MAX);
    }

    public static float jolt(float impulse) {
        return impulse <= 0 ? 0 : Math.clamp(JOLT_K * (float) (Math.log1p(impulse / JOLT_P0) / Math.log(2)), JOLT_MIN,
                JOLT_MAX);
    }

    /** {@code impulse} N s of one shot event; manual => aim kick + jolt, else jolt only. */
    public void shot(double now, float impulse, boolean manual, RandomGenerator random) {
        int k = count % SHOTS;
        shotAt[k] = now;
        applied[2 * k] = applied[2 * k + 1] = 0;
        uPitch[k] = random.nextFloat();
        uYaw[k] = random.nextFloat();
        float side = random.nextBoolean() ? 1 : -1, spread = 0.6F + 0.8F * random.nextFloat();
        if (manual) {
            float p = manualPeak(impulse);
            peak[k] = p;
            weight[3 * k + PITCH] = 0.5F * p;
            weight[3 * k + YAW] = 0;
            weight[3 * k + ROLL] = 0.4F * p * side * spread;
        } else {
            float j = jolt(impulse);
            peak[k] = 0;
            weight[3 * k + PITCH] = j;
            weight[3 * k + YAW] = 0.4F * j * (2 * random.nextFloat() - 1);
            weight[3 * k + ROLL] = 0.6F * j * side * spread;
        }
        if (Double.isNaN(prev)) {
            prev = now;
        }
        count++;
        lastShot = now;
    }

    /** {@code 0 -> hi} over {@link #RISE}, {@code hi -> lo} by {@link #SETTLE}, lo held. */
    static float curve(float t, float hi, float lo) {
        if (t <= 0) {
            return 0;
        }
        if (t < RISE) {
            return hi * t / RISE;
        }
        if (t < SETTLE) {
            return hi + (lo - hi) * (t - RISE) / (SETTLE - RISE);
        }
        return lo;
    }

    /** Aim offset of shot {@code k}, {@code t} s after it. */
    private void kick(int k, float t, float[] out) {
        float p = peak[k];
        float hiPitch = p * (0.7F + 0.3F * uPitch[k]), yaw = p * 0.35F * (2 * uYaw[k] - 1);
        out[0] = curve(t, yaw, 0.4F * yaw);
        out[1] = -curve(t, hiPitch, p * (0.15F + 0.1F * uPitch[k]));
    }

    static float recovery(double since, double dt) {
        double active = Math.min(dt, since + dt - RECOVERY_DELAY);
        return active <= 0 ? 1 : (float) Math.exp(-RECOVERY_RATE * active);
    }

    /** Aim delta {@code [yaw, pitch]} over {@code (previous step, now]}: kicks minus recovery. */
    public void step(double now, float[] out) {
        out[0] = out[1] = 0;
        if (count == 0) {
            return;
        }
        for (int i = Math.max(0, count - SHOTS); i < count; i++) {
            int k = i % SHOTS;
            if (peak[k] == 0) {
                continue;
            }
            kick(k, (float) Math.max(0, now - shotAt[k]), tmp);
            out[0] += tmp[0] - applied[2 * k];
            out[1] += tmp[1] - applied[2 * k + 1];
            applied[2 * k] = tmp[0];
            applied[2 * k + 1] = tmp[1];
        }
        acc[0] += out[0];
        acc[1] += out[1];
        float f = recovery(prev - lastShot, Math.max(0, now - prev));
        out[0] -= acc[0] * (1 - f);
        out[1] -= acc[1] * (1 - f);
        acc[0] *= f;
        acc[1] *= f;
        prev = now;
    }

    /**
     * Jolt deg on {@code channel}: shot weights through a damped spring ({@code omega} rad/s, {@code zeta < 1}), first
     * peak 1. Visual only.
     */
    public float spring(double now, int channel, float omega, float zeta) {
        float a = zeta * omega, b = omega * (float) Math.sqrt(1 - zeta * zeta);
        float tPeak = (float) Math.atan2(b, a) / b;
        float norm = 1 / ((float) Math.exp(-a * tPeak) * (float) Math.sin(b * tPeak));
        float sum = 0;
        for (int i = Math.max(0, count - SHOTS); i < count; i++) {
            int k = i % SHOTS;
            float t = (float) Math.max(0, now - shotAt[k]);
            if (a * t < 8) {
                sum += weight[3 * k + channel] * (float) Math.exp(-a * t) * (float) Math.sin(b * t);
            }
        }
        return sum * norm;
    }
}
