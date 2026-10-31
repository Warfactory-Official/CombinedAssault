package com.wf.combinedassault.hud;

public class SecondOrderDynamics {
    /** Fixed 0.05 s integration step per 6 ms of wall time (response speed is tuned to this ratio), catch-up capped. */
    private static final long STEP_NANOS = 6_000_000L;
    private static final int MAX_STEPS = 64;

    private final float k1;
    private final float k2;
    private final float k3;

    private float py;
    private float pyd;
    private float px;

    private float target;
    private long last = System.nanoTime();

    /** f: natural frequency. z: damping coefficient. r: initial velocity. x0: initial position. */
    public SecondOrderDynamics(float f, float z, float r, float x0) {
        k1 = (float) (z / (Math.PI * f));
        k2 = (float) (1 / ((2 * Math.PI * f) * (2 * Math.PI * f)));
        k3 = (float) (r * z / (2 * Math.PI * f));

        py = px = x0;
        pyd = 0;

        target = x0;
    }

    public float update(float x) {
        target = x;
        advance();
        return get();
    }

    public float get() {
        return py + 0.05f * pyd;
    }

    private void advance() {
        long now = System.nanoTime();
        long steps = Math.min((now - last) / STEP_NANOS, MAX_STEPS);
        last = steps == MAX_STEPS ? now : last + steps * STEP_NANOS;
        for (long i = 0; i < steps; i++) {
            float t = 0.05f;
            float xd = (target - px) / t;
            float y = py + t * pyd;

            pyd = pyd + t * (px + k3 * xd - py - k1 * pyd) / k2;
            px = target;
            py = y;
            if (Float.isNaN(py) || Float.isNaN(pyd)) {
                py = pyd = 0;
            }
        }
    }
}
