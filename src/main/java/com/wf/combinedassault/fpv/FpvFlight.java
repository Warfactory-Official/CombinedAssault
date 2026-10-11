package com.wf.combinedassault.fpv;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.vehicle.control.ControlUnit;

/**
 * FPV drones: direct attitude control, not the host's rate-cap + sideslip-limited model (treacle-handling on
 * a small frame). Closes a fixed fraction of angular error per tick instead; attitude-only, thrust still the
 * rotor disc. Opt-in via {@code attributes.fpv}; absent block => untouched stock flight.
 */
public final class FpvFlight {

    /** Fraction of angular error closed per tick. */
    private final double response;

    /** deg/tick from yaw keys; rate control, not an aim. */
    private final double yawRate;

    /** Extra nose-down while forward held: lets the throttle hand ask for speed too. */
    private final double pitchBias;

    /** Bank per deg/tick of yaw; cosmetic only, carries most of the feel. */
    private final double bankPerYaw;

    private final double maxBank;
    private final double maxPitch;

    private FpvFlight(JsonObject json) {
        this.response = Mth.clamp(GsonHelper.getAsDouble(json, "response", 0.35D), 0.01D, 1.0D);
        this.yawRate = GsonHelper.getAsDouble(json, "yaw_rate", 6.0D);
        this.pitchBias = GsonHelper.getAsDouble(json, "pitch_bias", 25.0D);
        this.bankPerYaw = GsonHelper.getAsDouble(json, "bank_per_yaw", 4.0D);
        this.maxBank = GsonHelper.getAsDouble(json, "max_bank", 45.0D);
        this.maxPitch = GsonHelper.getAsDouble(json, "max_pitch", 80.0D);
    }

    /** @return the model, or null if the airframe has no {@code attributes.fpv} block. */
    @Nullable
    public static FpvFlight of(@Nullable JsonObject json) {
        return json == null ? null : new FpvFlight(json);
    }

    /**
     * @param authority rotor output factor (engine speed * collective); 0 => rotors stopped, nothing to steer with.
     * @return true if attitude was set directly (caller skips the rate-limited model).
     */
    public boolean tickAttitude(RotaryWingVehicle vehicle, double authority) {
        if (authority <= 0 || vehicle.isDestroyed()) {
            // No thrust or destroyed: host physics takes over, not the operator.
            return false;
        }
        ControlUnit control = vehicle.controlUnit;

        float targetPitch = control.xRotKeep ? vehicle.getXRot() : control.xRot;
        if (control.forward) {
            targetPitch += (float) pitchBias;
        }
        if (control.backward) {
            targetPitch -= (float) pitchBias;
        }
        targetPitch = (float) Mth.clamp(targetPitch, -maxPitch, maxPitch);
        vehicle.setXRot(vehicle.getXRot() + (float) (Mth.wrapDegrees(targetPitch - vehicle.getXRot()) * response));

        float yawStep;
        if (control.leftYaw || control.rightYaw) {
            yawStep = (float) yawRate * ((control.rightYaw ? 1 : 0) - (control.leftYaw ? 1 : 0));
        } else if (control.yRotKeep) {
            yawStep = 0;
        } else {
            // Direct set, bypassing the host's sideslip limit (tail-rotor geometry a quadcopter lacks).
            yawStep = (float) (Mth.wrapDegrees(control.yRot - vehicle.getYRot()) * response);
        }
        vehicle.setYRot(vehicle.getYRot() + yawStep);

        float targetBank = (float) Mth.clamp(yawStep * bankPerYaw, -maxBank, maxBank);
        vehicle.setZRot(vehicle.getZRot() + (float) (Mth.wrapDegrees(targetBank - vehicle.getZRot()) * response));

        // Clears host rate state so a later revert to the rate-limited model (e.g. repair) starts clean.
        vehicle.xRotSpeed = 0;
        vehicle.yRotSpeed = 0;
        vehicle.zRotSpeed = 0;
        return true;
    }

    /** Ticks to close {@code fraction} of a step input (for describing the handling elsewhere). */
    public int ticksToClose(double fraction) {
        return (int) Math.ceil(Math.log(1 - fraction) / Math.log(1 - response));
    }

    public double response() {
        return response;
    }

}
