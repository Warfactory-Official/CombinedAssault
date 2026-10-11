package com.wf.combinedassault.naval;

import java.util.function.DoubleUnaryOperator;

/**
 * Surge/sway/yaw/heel/trim/heave of a floating hull, blocks+ticks.
 *
 * <p>No Minecraft/JOML deps => {@code tools/ShipCheck.java} drives it directly. In: pose, controls,
 * sea-probe result, masses. Out: velocity, attitude. Yaw returns through a callback (entity
 * collision-checks turns, may refuse).
 *
 * <p>Size-dependence, untuned per hull: yaw/sway tau = {@code L/v} (turn est./shed takes ~1 hull length
 * of travel); thrust = {@code m v_max / t_acc} (heavier hull, same engine => slower accel/brake). See
 * {@code REDACTED}.
 */
public final class ShipHydrodynamics {

    /** Physics engine's gravity/tick. */
    public static final double G = 9.8 / 400.0;
    public static final double TICKS_PER_SECOND = 20.0;

    /** Linear drag share vs quadratic term at v_max; bounds the coast-down. */
    private static final double LINEAR_DRAG = 0.3;
    /** Below: yaw tau stops growing, steerage lost. */
    private static final double STEERAGE_SPEED = 0.02;
    private static final double MIN_TAU = 2.0;
    private static final double MAX_TAU = 2400.0;
    /** Heave accel ceiling: dropped hull falls, doesn't snap to its line. */
    private static final double MAX_HEAVE_ACCEL = 4.0 * G;
    /** Depth past which submarine counts as under (speed + air). */
    private static final double SUBMERGED_DEPTH = 1.0;
    /** Per-axis velocity delta since last write, above which the world (contact/push) is taken to have
     * intervened. Below: engine deadband may zero a velocity smaller than 1 tick of a large hull's
     * thrust => model keeps its own. */
    private static final double ADOPT_THRESHOLD = 0.002;
    /** Heel/trim ceilings: caps a fast hull with a large heel figure rolling onto its beam. */
    private static final double MAX_HEEL = 30.0;
    private static final double MAX_TRIM = 15.0;

    /** Pack-declared tunables; defaults = mid-size surface ship. */
    public static final class Params {
        public double waterline = 0.0;          // blocks: origin Y -> waterline
        public double draft = 2.0;              // blocks below waterline
        public double maxSpeedForward = 0.5;    // blocks/tick
        public double maxSpeedBackward = 0.2;   // blocks/tick
        public double accelerationTime = 10.0;  // seconds to v_max
        public double throttleRate = 0.5;       // full scale/second
        public double rudderRate = 1.0;         // full scale/second
        public double turnRate = 10.0;          // deg/s, full rudder+speed
        public double turnLag = 1.0;            // multiplier on L/v tau
        public double lowSpeedTurn = 0.0;       // turn authority floor, 0..1
        public double drift = 0.15;             // multiplier, yaw tau -> sway tau
        public double heel = 1.0;               // deg roll per deg/s yaw rate
        public double trim = 2.0;               // deg pitch at full thrust
        public double heavePeriod = 3.0;        // seconds
        public double rightingRate = 4.0;       // deg/s
        public double minKeelClearance = 1.0;   // blocks
        public double shallowDrag = 2.0;        // drag multiplier at zero clearance
        public boolean submarine = false;
        public double maxDepth = 40.0;          // blocks
        public double crushDepth = 60.0;        // blocks
        public double diveRate = 0.15;          // blocks/tick
        public double maxSpeedSubmerged = 0.4;  // blocks/tick
        public double diveAngle = 12.0;         // deg at full dive error
    }

    public static final class Controls {
        public boolean ahead, astern, port, starboard, dive, rise;
        /** Hull not moving through water regardless of thrust: aground or against a bank. */
        public boolean blocked;
        /** World contacts bore the hull last step: attitude theirs. Kinematic levelling into the bed => push-out lifts hull. */
        public boolean supported;
        /** Engine output, 0..1. */
        public double power = 1.0;
        /** Component-damage scales from {@code VehicleMobility}. */
        public double speedScale = 1.0;
        public double turnScale = 1.0;
    }

    /** What the sea probe found under the hull this tick. */
    public static final class Environment {
        public boolean afloat;
        public double surfaceY = Double.NaN;
        /** Blocks of water under the keel at the shallowest sampled column; NaN when unknown. */
        public double keelClearance = Double.NaN;
        /** Surface to seabed at the shallowest column; NaN when unknown. */
        public double waterDepth = Double.NaN;
    }

    /** The hull's pose and velocity, read and written in place. Yaw is only read; it moves through the callback. */
    public static final class Pose {
        public double y;
        public float yaw, pitch, roll;
        public double vx, vy, vz;
    }

    // Persistent state.
    public double throttle;
    public double rudder;
    /** Set on a direct helm order; rudder holds until the controls are touched. */
    public boolean rudderHeld;
    /** Degrees per tick, positive to starboard. */
    public double yawRate;
    /** Submarines: blocks below the surface the waterline mark is set to. */
    public double depthSetting;

    // Readouts for the HUD and tests, refreshed by every step.
    public double surge;
    public double sway;
    public double depth;
    public boolean shallow;
    public boolean submerged;
    /** Whether this tick's yaw was refused by the world. */
    public boolean turnBlocked;
    /** Horizontal velocity written last tick; distinguishes world changes from engine deadband. */
    private double writtenVx = Double.NaN, writtenVz;

    /**
     * Advances one tick.
     *
     * @param hullLength the physics hull's length in blocks
     * @param maxYawRate the engine's tip-speed clamp, degrees per tick
     * @param turn applies a yaw change and returns what the world allowed
     */
    public void step(Params p, Pose pose, Controls c, Environment env, double mass, double curbMass,
                     double hullLength, double maxYawRate, DoubleUnaryOperator turn) {
        stepControls(p, c, env);

        // Body frame: forward = yaw dir, left = port.
        double yawRad = Math.toRadians(pose.yaw);
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double lx = fz, lz = -fx;
        double u = pose.vx * fx + pose.vz * fz;
        double w = pose.vx * lx + pose.vz * lz;
        if (!Double.isNaN(writtenVx) && Math.abs(pose.vx - writtenVx) < ADOPT_THRESHOLD
                && Math.abs(pose.vz - writtenVz) < ADOPT_THRESHOLD) {
            u = surge;
            w = sway;
        }

        if (!env.afloat) {
            // Beached/airborne: no thrust/steerage; gravity+contacts own the hull.
            surge = u;
            sway = w;
            writtenVx = Double.NaN;
            yawRate = 0;
            shallow = false;
            submerged = false;
            depth = 0;
            settleAttitude(p, c, pose, 0, 0);
            return;
        }

        depth = env.surfaceY - (pose.y + p.waterline);
        submerged = p.submarine && depth > SUBMERGED_DEPTH;
        double vMaxAhead = (submerged ? p.maxSpeedSubmerged : p.maxSpeedForward) * c.speedScale;
        double vMaxAstern = p.maxSpeedBackward * c.speedScale;
        double massRatio = curbMass > 0 && mass > 0 ? curbMass / mass : 1.0;
        double tauAcc = Math.max(1.0, p.accelerationTime * TICKS_PER_SECOND);

        shallow = !Double.isNaN(env.keelClearance) && env.keelClearance < p.minKeelClearance;
        double dragMul = 1.0;
        if (shallow) {
            double closeness = 1.0 - Math.max(0, env.keelClearance) / Math.max(1.0e-6, p.minKeelClearance);
            dragMul += p.shallowDrag * closeness;
        }

        if (c.blocked) {
            // Blocked: water carries the hull nowhere; zero u/w regardless of throttle/rudder.
            u = 0;
            w = 0;
        }

        // Surge: thrust vs quadratic+linear drag, scaled so full throttle settles at exactly v_max.
        double vRef = Math.max(1.0e-4, throttle >= 0 ? vMaxAhead : vMaxAstern);
        double accel = vRef / tauAcc * massRatio;
        double thrust = throttle * c.power * accel * (1.0 + LINEAR_DRAG);
        double dragRef = Math.max(1.0e-4, u >= 0 ? vMaxAhead : vMaxAstern);
        double x = u / dragRef;
        double drag = (dragRef / tauAcc) * massRatio * dragMul * (x * Math.abs(x) + LINEAR_DRAG * x);
        u += thrust - drag;
        u = clamp(u, -vMaxAstern * 1.05, vMaxAhead * 1.05);

        // Yaw: steady turn scales with speed; hull follows with its own lag.
        double speedFactor = Math.abs(u) / Math.max(1.0e-4, vMaxAhead);
        double authority = Math.max(speedFactor, p.lowSpeedTurn) * c.turnScale;
        double sign = u >= 0 ? 1.0 : -1.0;
        double rTarget = p.turnRate / TICKS_PER_SECOND * rudder * authority * sign;
        double tauYaw = clamp(p.turnLag * hullLength / Math.max(Math.abs(u), STEERAGE_SPEED), MIN_TAU, MAX_TAU);
        yawRate += (rTarget - yawRate) / tauYaw;
        yawRate = clamp(yawRate, -maxYawRate, maxYawRate);
        double asked = Math.abs(yawRate) < 1.0e-5 ? 0 : yawRate;
        double got = asked == 0 ? 0 : turn.applyAsDouble(asked);
        turnBlocked = asked != 0 && got == 0;
        if (turnBlocked) {
            yawRate = 0;
        }

        // Sway: heading change leaves old velocity partly across new heading; water resists with its own lag.
        double turned = Math.toRadians(got);
        double uNew = u * Math.cos(turned) + w * Math.sin(turned);
        double wNew = -u * Math.sin(turned) + w * Math.cos(turned);
        double tauSway = clamp(p.drift * tauYaw, 1.0, MAX_TAU);
        wNew *= Math.exp(-1.0 / tauSway);
        u = uNew;
        w = wNew;

        // Heave: critically-damped spring onto waterline; afloat => no engine gravity.
        double target = env.surfaceY - p.waterline - depthSetting;
        double omega = 2.0 * Math.PI / Math.max(1.0, p.heavePeriod * TICKS_PER_SECOND);
        double heave = omega * omega * (target - pose.y) - 2.0 * omega * pose.vy;
        heave = clamp(heave, -MAX_HEAVE_ACCEL, MAX_HEAVE_ACCEL);
        double vy = pose.vy + heave;

        // Compose in the new frame.
        double newYaw = Math.toRadians(pose.yaw + got);
        double nfx = -Math.sin(newYaw), nfz = Math.cos(newYaw);
        double nlx = nfz, nlz = -nfx;
        pose.vx = u * nfx + w * nlx;
        pose.vz = u * nfz + w * nlz;
        pose.vy = vy;
        writtenVx = pose.vx;
        writtenVz = pose.vz;
        surge = u;
        sway = w;

        // Attitude: heel tracks turn, trim tracks thrust, dive tracks depth error.
        double rollTarget = clamp(-p.heel * yawRate * TICKS_PER_SECOND * speedFactor, -MAX_HEEL, MAX_HEEL);
        double pitchTarget = clamp(-p.trim * clamp(thrust / Math.max(1.0e-6, accel * (1.0 + LINEAR_DRAG)), -1, 1),
                -MAX_TRIM, MAX_TRIM);
        if (p.submarine) {
            double error = target - pose.y;
            pitchTarget += p.diveAngle * clamp(-error / 4.0, -1, 1);
        }
        settleAttitude(p, c, pose, pitchTarget, rollTarget);
    }

    private void stepControls(Params p, Controls c, Environment env) {
        double throttleStep = p.throttleRate / TICKS_PER_SECOND;
        if (c.ahead && !c.astern) {
            throttle = Math.min(1.0, throttle + throttleStep);
        } else if (c.astern && !c.ahead) {
            throttle = Math.max(-1.0, throttle - throttleStep);
        }
        double rudderStep = p.rudderRate / TICKS_PER_SECOND;
        if (c.starboard && !c.port) {
            rudder = Math.min(1.0, rudder + rudderStep);
            rudderHeld = false;
        } else if (c.port && !c.starboard) {
            rudder = Math.max(-1.0, rudder - rudderStep);
            rudderHeld = false;
        } else if (!rudderHeld) {
            rudder = approach(rudder, 0, rudderStep);
        }
        if (!p.submarine) {
            depthSetting = 0;
            return;
        }
        if (c.dive && !c.rise) {
            depthSetting += p.diveRate;
        } else if (c.rise && !c.dive) {
            depthSetting -= p.diveRate;
        }
        double deepest = p.maxDepth;
        if (!Double.isNaN(env.waterDepth)) {
            deepest = Math.min(deepest, env.waterDepth - p.draft - p.minKeelClearance);
        }
        depthSetting = clamp(depthSetting, 0, Math.max(0, deepest));
    }

    private void settleAttitude(Params p, Controls c, Pose pose, double pitchTarget, double rollTarget) {
        if (c.supported) {
            return;
        }
        float step = (float) (p.rightingRate / TICKS_PER_SECOND);
        pose.pitch = approachDegrees(pose.pitch, (float) pitchTarget, step);
        pose.roll = approachDegrees(pose.roll, (float) rollTarget, step);
    }

    public boolean crushed(Params p) {
        return p.submarine && depth > p.crushDepth;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static double approach(double v, double target, double step) {
        if (v < target) {
            return Math.min(target, v + step);
        }
        return Math.max(target, v - step);
    }

    static float approachDegrees(float v, float target, float step) {
        float diff = wrapDegrees(target - v);
        float moved = Math.abs(diff) < step ? diff : Math.copySign(step, diff);
        return wrapDegrees(v + moved);
    }

    static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }
}
