package com.wf.combinedassault.vehicle.solver;

import net.minecraft.network.FriendlyByteBuf;
import org.joml.Vector3f;

/**
 * Arcade flight response for rotary- and fixed-wing vehicles: attitude is driven, not solved.
 * Contact solver owns translation and impacts; flight model drives orientation. Implements
 * drag and control authority ramping. Not a lift/drag model.
 */
public final class AircraftAerodynamics {

    /**
     * Horizontal drag {@code 1 - floorDrag - c2 v^2}, c2 so disc-tilt thrust at {@link #topSpeedTilt} balances it at
     * top speed. Linear {@code 0.95 - 0.015 v} FORBIDDEN: every helicopter capped ~40 km/h at 28 deg.
     */
    public float floorDrag = 0.003f;

    /** Pitch (deg) at which level flight reaches top speed. */
    public float topSpeedTilt = 20;

    /** Vertical velocity retained per tick with the rotor at full output; falls free as output drops. */
    public float verticalDrag = 0.95f;
    public float freeFallDrag = 0.995f;

    /** Per-tick roll retained with no input: an aircraft rolls level on its own. */
    public float rollCentring = 0.99f;

    /** Roll and pitch retained per tick while on the ground, so it settles flat. */
    public float groundLevelling = 0.98f;

    /** Ticks of held input over which control authority ramps to full. */
    public int authorityRampTicks = 7;

    /**
     * Maximum scale of rotor thrust to compensate for tilt; prevents a near-inverted machine from
     * generating absurd lift and avoids requiring constant collective input to translate.
     */
    public float maxTiltCompensation = 1.6f;

    /** Below this vertical component of the rotor axis, tilt compensation clamps to avoid divergence. */
    public float minTiltCosine = 0.35f;

    /**
     * Scale of disc tilt relative to airframe tilt. Helicopter acceleration without this is
     * gravity times tan(tilt), which at this mod's gravity would halve the speed Superb Warfare
     * reaches with the same lean. Applies to horizontal thrust only; vertical is untouched.
     */
    public float discTiltGain = 1.8f;

    /** Maximum collective change per tick for altitude hold, as a fraction of full range. */
    public float altitudeHoldRate = 0.006f;
    public float hoverHoldRate = 0.02f;

    /** Mouse-yaw coordinated-turn bank (deg). Unbanked => velocity never turns; heading pinned by sideslip limit. */
    public float maxTurnBank = 45;

    /** Hover: PD natural frequency (rad/tick), tilt clamp (deg), anchor capture speed and climb clamp (blocks/tick). */
    public float hoverBandwidth = 0.05f;
    public float hoverMaxTilt = 30;
    public float hoverCaptureSpeed = 0.15f;
    public float hoverClimbRate = 0.2f;
    /** Ticks of vertical acceleration added to the hover climb error (damping). */
    public float hoverClimbLead = 10;

    /**
     * Collective correction per unit vertical speed, as a fraction of full range. Proportional
     * control rather than fixed step to converge; high gain causes oscillation above 0.4.
     */
    public float altitudeHoldGain = 0.12f;

    /**
     * Vertical speed band inside which altitude hold does nothing. Float-noise guard that prevents
     * proportional correction from converging to the edge.
     */
    public float altitudeHoldDeadband = 0.0002f;

    /** Fraction of lateral velocity shed per tick; prevents fixed-wing from drifting sideways cheaply. */
    public float sideslipDrag = 0.15f;

    private int heldTicks;

    /** Authority ramp (snapshot, docs/client-prediction.md). */
    public void writePrediction(FriendlyByteBuf buf) {
        buf.writeVarInt(heldTicks);
    }

    public void readPrediction(FriendlyByteBuf buf) {
        heldTicks = buf.readVarInt();
    }

    /**
     * Control authority for an input held this many ticks. A tap nudges, hold commits.
     * @return authority as a fraction 0 to 1
     */
    public float authority(boolean held) {
        heldTicks = held ? Math.min(heldTicks + 1, authorityRampTicks) : 0;
        return authorityRampTicks <= 0 ? 1 : (float) heldTicks / authorityRampTicks;
    }

    public void resetAuthority() {
        heldTicks = 0;
    }

    /**
     * Applies drag to a velocity in place.
     * @param velocity blocks per tick, modified in place
     * @param topSpeed blocks per tick
     * @param gravity blocks per tick squared
     * @param rotorOutput 0..1: power x rotor lift, collective aside
     */
    public void applyToVelocity(Vector3f velocity, float topSpeed, float gravity, float rotorOutput) {
        // Pitch coupling (+3.5 %/tick of speed past 5 deg) FORBIDDEN: under this drag it compounded to top speed in 4 s.
        float thrust = gravity * (float) Math.tan(Math.toRadians(topSpeedTilt)) * discTiltGain;
        float c2 = Math.max(0, (thrust / topSpeed - floorDrag) / (topSpeed * topSpeed));
        float horizontalSq = velocity.x * velocity.x + velocity.z * velocity.z;
        float drag = clamp(1 - floorDrag - c2 * horizontalSq, 0, 1);
        velocity.x *= drag;
        velocity.z *= drag;
        velocity.y *= freeFallDrag + (verticalDrag - freeFallDrag) * clamp(rotorOutput, 0, 1);
    }

    /** Rolls level over time, and flattens onto the ground when there. */
    public float settleRoll(float rollDeg, boolean rollInput, boolean onGround) {
        if (onGround) {
            return rollDeg * groundLevelling;
        }
        return rollInput ? rollDeg : rollDeg * rollCentring;
    }

    /** Pitch flattens on the ground only; in the air it is entirely the pilot's. */
    public float settlePitch(float pitchDeg, boolean onGround) {
        return onGround ? pitchDeg * groundLevelling : pitchDeg;
    }

    /** Lateral velocity to bleed per tick. Apply along the aircraft's right axis. */
    public float sideslipBleed(float lateralVelocity) {
        return lateralVelocity * sideslipDrag;
    }

    /**
     * Scale to restore vertical thrust when the rotor is tilted. Thrust acts along the machine's
     * up axis, so tilt costs vertical component; restoring it lets the helicopter translate at
     * constant height.
     * @param upY vertical component of vehicle's up axis
     */
    public float liftTiltCompensation(float upY) {
        if (upY <= minTiltCosine) {
            return maxTiltCompensation;
        }
        return Math.min(maxTiltCompensation, 1.0f / upY);
    }

    /**
     * Collective correction to hold altitude, as a fraction of full range. Deadbanded to prevent
     * hunting. Must be sampled after gravity if measuring the velocity before gravity causes a
     * permanent sink of one tick.
     * @param verticalVelocity vertical velocity after gravity
     * @return collective adjustment, zero inside deadband
     */
    public float altitudeHoldDelta(float verticalVelocity) {
        return altitudeHoldDelta(verticalVelocity, altitudeHoldRate);
    }

    public float altitudeHoldDelta(float verticalVelocity, float rate) {
        if (Math.abs(verticalVelocity) <= altitudeHoldDeadband) {
            return 0;
        }
        return clamp(-verticalVelocity * altitudeHoldGain, -rate, rate);
    }

    /**
     * Pitch for a grounded aircraft derived from gear heights. Kinematic attitude adjustment based
     * on terrain slope, not a solver.
     * @param frontDrop height of forward gear above surface
     * @param rearDrop height of rear gear above surface
     * @param wheelbase distance between gear along fuselage
     * @return target pitch in degrees, positive nose-down
     */
    public static float terrainPitch(float frontDrop, float rearDrop, float wheelbase) {
        if (wheelbase <= 1.0e-4f) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(frontDrop - rearDrop, wheelbase));
    }

    /** Moves an angle a fraction of the way toward a target, the shortest way round. */
    public static float lerpAngle(float current, float target, float rate) {
        float delta = target - current;
        while (delta > 180) {
            delta -= 360;
        }
        while (delta < -180) {
            delta += 360;
        }
        return current + delta * rate;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

}
