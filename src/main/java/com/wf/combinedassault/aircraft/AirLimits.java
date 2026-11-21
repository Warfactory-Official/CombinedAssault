package com.wf.combinedassault.aircraft;

import com.wf.combinedassault.vehicle.VehicleConfig;

public final class AirLimits {

    public static final float HARD_DEFAULT = 1.1f;
    public static final float THRUST_FLOOR = 0.1f;

    private AirLimits() {
    }

    /** Pack ceiling, blocks; {@code heightCaps} off => infinite. */
    public static double ceiling(float blocks) {
        return VehicleConfig.server().heightCaps() ? blocks : Double.POSITIVE_INFINITY;
    }

    /** {@link #ceiling} x config ratio. */
    public static double scaled(float blocks) {
        return ceiling(blocks) * AircraftConfig.get().ceilingRatio();
    }

    /** {@code hard <= 0} => {@link #HARD_DEFAULT} x soft. Build height deliberately not a bound. */
    public static double hard(float soft, float hard) {
        return scaled(hard > 0 ? hard : soft * HARD_DEFAULT);
    }

    /** Engine air fraction: 1 to y=64, linear to {@link #THRUST_FLOOR} at the soft ceiling, floor to the hard ceiling, 0 above (flameout). Infinite soft => 1. */
    public static float thrust(double y, double soft, double hard) {
        if (y >= hard) {
            return 0;
        }
        if (y <= 64 || soft <= 64 || Double.isInfinite(soft)) {
            return 1;
        }
        double d = (soft - y) / (soft - 64);
        return (float) Math.max(THRUST_FLOOR, Math.min(1, d));
    }

    /** Air density factor: 1 to y=64, {@code ((ceiling - y) / (ceiling - 64))^exponent} to 0 at the ceiling. Infinite ceiling => 1. */
    public static double density(double y, double ceiling, double exponent) {
        if (y < 64 || Double.isInfinite(ceiling)) {
            return 1;
        }
        return Math.pow(Math.max(0, ceiling - y), exponent) / Math.pow(ceiling - 64, exponent);
    }
}
