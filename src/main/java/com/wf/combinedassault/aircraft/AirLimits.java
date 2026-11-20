package com.wf.combinedassault.aircraft;

public final class AirLimits {

    public static final float HARD_DEFAULT = 1.1f;
    public static final float THRUST_FLOOR = 0.1f;

    private AirLimits() {
    }

    /** Pack ceiling x config ratio, blocks. */
    public static double scaled(float blocks) {
        return blocks * AircraftConfig.get().ceilingRatio();
    }

    /** {@code hard <= 0} => {@link #HARD_DEFAULT} x soft. Build height deliberately not a bound. */
    public static double hard(float soft, float hard) {
        return scaled(hard > 0 ? hard : soft * HARD_DEFAULT);
    }

    /** Engine air fraction: 1 to y=64, linear to {@link #THRUST_FLOOR} at the soft ceiling, floor to the hard ceiling, 0 above (flameout). */
    public static float thrust(double y, double soft, double hard) {
        if (y >= hard) {
            return 0;
        }
        if (y <= 64 || soft <= 64) {
            return 1;
        }
        double d = (soft - y) / (soft - 64);
        return (float) Math.max(THRUST_FLOOR, Math.min(1, d));
    }
}
