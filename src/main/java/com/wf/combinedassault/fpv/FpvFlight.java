package com.wf.combinedassault.fpv;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.vehicle.control.ControlUnit;

/**
 * FPV drones: acro (body-rate) control, no self-levelling, no attitude limits. Schema + controls: pack format §8.2.
 */
public final class FpvFlight {

    /** deg/tick from keys. */
    private final double pitchRate;
    private final double rollRate;
    private final double yawRate;
    /** Lens uptilt over the body's forward axis, deg. */
    private final double cameraTilt;

    private FpvFlight(JsonObject json) {
        this.pitchRate = GsonHelper.getAsDouble(json, "pitch_rate", 12.0D);
        this.rollRate = GsonHelper.getAsDouble(json, "roll_rate", 15.0D);
        this.yawRate = GsonHelper.getAsDouble(json, "yaw_rate", 8.0D);
        this.cameraTilt = GsonHelper.getAsDouble(json, "camera_tilt", 0.0D);
    }

    /** @return the model, or null if the airframe has no {@code attributes.fpv} block. */
    @Nullable
    public static FpvFlight of(@Nullable JsonObject json) {
        return json == null ? null : new FpvFlight(json);
    }

    /**
     * Reported attitude adopted unchecked: mouse turn is unbounded input, any attitude reachable in one tick. No
     * new input => last report held, nothing re-stepped.
     *
     * @param authority rotor output factor (engine speed * collective); 0 => rotors stopped, nothing to steer with.
     * @return true if attitude was set directly (caller skips the rate-limited model).
     */
    public boolean tickAttitude(RotaryWingVehicle vehicle, double authority) {
        if (authority <= 0 || vehicle.isDestroyed()) {
            return false;
        }
        ControlUnit c = vehicle.controlUnit;
        if (c.reported) {
            vehicle.setYRot(c.reportedYRot);
            vehicle.setXRot(c.reportedXRot);
            vehicle.setZRot(c.reportedZRot);
        } else if (c.turnX != 0 || c.turnY != 0 || keyed(c)) {
            setAttitude(vehicle, step(vehicle.rotYXZ(), c, c.turnX, c.turnY, keyTicks(vehicle, 1)));
        }
        vehicle.xRotSpeed = 0;
        vehicle.yRotSpeed = 0;
        vehicle.zRotSpeed = 0;
        return true;
    }

    /**
     * Operator client at input sample, before the new keys land in {@code vehicle.controlUnit}: one full step of the
     * keys {@link #lead} extrapolated since the last sample => no jump when they change.
     */
    public void clientStep(RotaryWingVehicle vehicle, float turnX, float turnY) {
        if (vehicle.fpvAuthority() <= 0 || vehicle.isDestroyed()) {
            return;
        }
        ControlUnit c = vehicle.controlUnit;
        if (turnX != 0 || turnY != 0 || keyed(c)) {
            setAttitude(vehicle, step(vehicle.rotYXZ(), c, turnX, turnY, keyTicks(vehicle, 1)));
        }
    }

    /** Render attitude: last sample + {@code partialTick} of its keys + mouse turn not yet sampled. */
    public Quaternionf lead(RotaryWingVehicle vehicle, float turnX, float turnY, float partialTick) {
        if (vehicle.fpvAuthority() <= 0 || vehicle.isDestroyed()) {
            return vehicle.rotYXZ();
        }
        return step(vehicle.rotYXZ(), vehicle.controlUnit, turnX, turnY, keyTicks(vehicle, partialTick));
    }

    /** {@code turnY} about world vertical, signed by body up; keys + {@code turnX} body frame. */
    private Quaternionf step(Quaternionf rot, ControlUnit c, float turnX, float turnY, float keyTicks) {
        float pitch = turnX + keyTicks * (float) pitchRate * (axis(c.forward) - axis(c.backward));
        float yaw = keyTicks * (float) yawRate * (axis(c.rightYaw) - axis(c.leftYaw));
        float roll = keyTicks * (float) rollRate * (axis(c.right) - axis(c.left));
        float heading = rot.transformUnit(new Vector3f(0, 1, 0)).y < 0 ? -turnY : turnY;
        return new Quaternionf().rotationY((float) Math.toRadians(-heading)).mul(rot)
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
    }

    /** Key rates / {@link RotaryWingVehicle#load}: cargo inertia. Mouse turn exempt (unbounded input). */
    private static float keyTicks(RotaryWingVehicle vehicle, float ticks) {
        return (float) (ticks / vehicle.load());
    }

    private static boolean keyed(ControlUnit c) {
        return c.forward || c.backward || c.left || c.right || c.leftYaw || c.rightYaw;
    }

    /** Inverse of {@code rotYXZ}; yaw kept continuous with the current heading. */
    public static void setAttitude(RotaryWingVehicle vehicle, Quaternionf rot) {
        Vector3f euler = rot.getEulerAnglesYXZ(new Vector3f());
        float yRot = (float) Math.toDegrees(-euler.y);
        vehicle.setYRot(vehicle.getYRot() + Mth.wrapDegrees(yRot - vehicle.getYRot()));
        vehicle.setXRot((float) Math.toDegrees(euler.x));
        vehicle.setZRot((float) Math.toDegrees(euler.z));
    }

    private static int axis(boolean down) {
        return down ? 1 : 0;
    }

    public double cameraTilt() {
        return cameraTilt;
    }

}
