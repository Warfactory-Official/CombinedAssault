package com.wf.combinedassault.weapon.client;

import com.wf.combinedassault.vehicle.VehicleConfig;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.Util;
import net.minecraft.util.Mth;

/** Rolling-surface vibration of a vehicle without running gear: grounded, scales with ground speed. */
public final class VehicleVibration {
    /** m/s at which amplitude saturates; below {@link #MIN_SPEED} none. */
    static final float FULL_SPEED = 30, MIN_SPEED = 0.5F;
    /** Peak deg at {@link #FULL_SPEED}. */
    static final float PITCH_DEG = 0.30F, YAW_DEG = 0.12F, ROLL_DEG = 0.25F;
    static final float SMOOTH_PER_SEC = 6;
    private static final float[] HZ_PITCH = {13.1F, 21.7F}, HZ_YAW = {9.3F, 17.9F}, HZ_ROLL = {11.3F, 23.3F};

    private static float amplitude;
    private static long lastMillis;

    private VehicleVibration() {
    }

    static float amplitudeFor(AbstractVehicle vehicle) {
        if (vehicle.hasRunningGear() || !vehicle.onGround()) {
            return 0;
        }
        double dx = vehicle.getDeltaMovement().x, dz = vehicle.getDeltaMovement().z;
        float speed = (float) Math.sqrt(dx * dx + dz * dz) * 20;
        return speed < MIN_SPEED ? 0 : Math.min(1, speed / FULL_SPEED);
    }

    private static float wave(float[] hz, double seconds) {
        return 0.65F * Mth.sin((float) (seconds * hz[0] * Mth.TWO_PI % Mth.TWO_PI))
                + 0.35F * Mth.sin((float) (seconds * hz[1] * Mth.TWO_PI % Mth.TWO_PI));
    }

    /** Adds {@code [yaw, pitch, roll]} deg to {@code out}, camera-angle signs. */
    public static void add(AbstractVehicle vehicle, float[] out) {
        long now = Util.getMillis();
        float dt = lastMillis == 0 ? 0 : Math.min(0.1F, (now - lastMillis) / 1000F);
        lastMillis = now;
        amplitude += (amplitudeFor(vehicle) - amplitude) * Math.min(1, SMOOTH_PER_SEC * dt);
        if (amplitude < 1e-3F) {
            return;
        }
        double seconds = now / 1000.0;
        float scale = amplitude * (float) VehicleConfig.common().cameraShakeMultiplier();
        out[0] += YAW_DEG * scale * wave(HZ_YAW, seconds);
        out[1] += PITCH_DEG * scale * wave(HZ_PITCH, seconds);
        out[2] += ROLL_DEG * scale * wave(HZ_ROLL, seconds);
    }
}
