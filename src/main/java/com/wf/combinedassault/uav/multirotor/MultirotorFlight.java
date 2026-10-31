package com.wf.combinedassault.uav.multirotor;

import com.google.gson.JsonObject;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.uav.fpv.FpvFlight;
import com.wf.combinedassault.dynamics.PhysicsHelper;
import com.wf.combinedassault.dynamics.PhysicsEngine;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.wflib.geom.api.flight.Airframe;
import com.wf.wflib.geom.api.flight.FlightAttitude;
import com.wf.wflib.geom.api.flight.Multirotor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Multirotor flight on wflib {@link Multirotor}: thrust only along the tilted disc, drag from the airframe, real
 * thrust-to-weight. Replaces the helicopter force law ({@code attributes.multirotor}); mass/curb = load factor.
 * STABILIZED: keys = velocity setpoint (same controller the autopilot flies). FPV: {@link FpvFlight} attitude,
 * collective = throttle.
 */
public final class MultirotorFlight {

    private static final float TUMBLE_MAX = 1.0F;
    private static final float TUMBLE_DAMPING = 0.9F;

    /** N, all rotors at full output. */
    private final double thrust;
    private final double maxTilt;
    private final double tiltRate;
    private final double linearDrag;
    private final double quadraticDrag;
    private final double velocityGain;
    private final double verticalGain;
    private final double maxClimb;
    private final double maxDescent;
    /** STABILIZED stick authority, blocks/tick. */
    private final double maxSpeed;
    private final double climbSpeed;
    private final double yawRate;
    private final double yawResponse;
    private final boolean defaultFpv;
    private final FpvFlight fpvAttitude;

    /** Crippled tumble, body frame, rad/tick. */
    private final Vector3f spin = new Vector3f();
    private FlightAttitude attitude = FlightAttitude.STOPPED;
    private boolean fpv;
    /** Autopilot setpoint for this tick, consumed by {@link #tick}; null = stick. */
    @Nullable
    private Vec3 commanded;
    private Vec3 commandedFeedForward = Vec3.ZERO;
    private float commandedYaw;

    private MultirotorFlight(JsonObject json) {
        this.thrust = GsonHelper.getAsDouble(json, "thrust");
        this.maxTilt = Math.toRadians(GsonHelper.getAsDouble(json, "max_tilt", 45.0));
        this.tiltRate = Math.toRadians(GsonHelper.getAsDouble(json, "tilt_rate", 10.0));
        this.linearDrag = GsonHelper.getAsDouble(json, "linear_drag", 0.04);
        this.quadraticDrag = GsonHelper.getAsDouble(json, "quadratic_drag", 0.02);
        this.velocityGain = GsonHelper.getAsDouble(json, "velocity_gain", 0.8);
        this.verticalGain = GsonHelper.getAsDouble(json, "vertical_gain", 0.28);
        this.maxClimb = GsonHelper.getAsDouble(json, "max_climb", 0.6);
        this.maxDescent = GsonHelper.getAsDouble(json, "max_descent", 0.45);
        this.maxSpeed = GsonHelper.getAsDouble(json, "max_speed", 0.9);
        this.climbSpeed = GsonHelper.getAsDouble(json, "climb_speed", 0.3);
        this.yawRate = GsonHelper.getAsDouble(json, "yaw_rate", 8.0);
        this.yawResponse = Mth.clamp(GsonHelper.getAsDouble(json, "yaw_response", 0.35), 0.01, 1.0);
        this.defaultFpv = "fpv".equals(GsonHelper.getAsString(json, "mode", "stabilized"));
        this.fpvAttitude = FpvFlight.of(GsonHelper.getAsJsonObject(json, "fpv", new JsonObject()));
        this.fpv = this.defaultFpv;
    }

    @Nullable
    public static MultirotorFlight of(@Nullable JsonObject json) {
        return json == null ? null : new MultirotorFlight(json);
    }

    public boolean fpv() {
        return this.fpv;
    }

    public void setFpv(boolean fpv) {
        this.fpv = fpv;
    }

    public FpvFlight fpvAttitude() {
        return this.fpvAttitude;
    }

    public FlightAttitude attitude() {
        return this.attitude;
    }

    /** Autopilot: fly {@code desired} (blocks/tick) facing {@code yawDeg} this tick, stick ignored. */
    public void command(Vec3 desired, Vec3 feedForward, float yawDeg) {
        this.commanded = desired;
        this.commandedFeedForward = feedForward;
        this.commandedYaw = yawDeg;
    }

    /**
     * @param authority rotor output 0..1 (engine power x rotor lift); 0 => no descent limit (no rotor wash)
     * @param acro FPV: no vertical limits, drag alone (inverted full throttle dives faster than freefall)
     */
    public Airframe airframe(AbstractVehicle vehicle, double authority, boolean acro) {
        return new Airframe(PhysicsEngine.G, this.thrust * authority / (vehicle.curbWeight * PhysicsHelper.GRAVITY),
                this.maxTilt, this.tiltRate / vehicle.load(), this.linearDrag, this.quadraticDrag, this.velocityGain,
                this.verticalGain, acro ? Double.MAX_VALUE : this.maxClimb,
                acro || authority <= 0 ? Double.MAX_VALUE : this.maxDescent, 0.0);
    }

    /**
     * @param authority engine power x balanced rotor share, 0..1; 0 or destroyed => falls under drag
     * @param crippled  rotors left that cannot balance (power on): each holds the last collective => tumble; else null
     */
    public void tick(RotaryWingVehicle vehicle, double authority, @Nullable RotorLayout crippled, double power) {
        Vec3 desired = this.commanded;
        this.commanded = null;
        boolean dead = authority <= 0 || vehicle.isDestroyed();
        Airframe frame = this.airframe(vehicle, dead ? 0.0 : authority, !dead && desired == null && this.fpv);
        double massFactor = vehicle.physicsEngine.mass / vehicle.curbWeight;
        Vec3 velocity = vehicle.getDeltaMovement();
        Vec3 next;
        if (dead && crippled != null && !vehicle.isDestroyed()) {
            next = this.tumble(vehicle, crippled, power, velocity, massFactor);
        } else if (dead) {
            this.spin.zero();
            this.attitude = FlightAttitude.STOPPED;
            next = Multirotor.integrate(velocity, Vec3.ZERO, 0.0, frame);
        } else if (desired == null && this.fpv) {
            this.spin.zero();
            this.fpvAttitude.tickAttitude(vehicle, authority);
            Vec3 axis = vehicle.relativeRotDirection(new Vec3(0, 1, 0), false);
            double specific = vehicle.getCollectivePitch() / 100.0 * frame.maxSpecificThrust(massFactor);
            next = Multirotor.integrate(velocity, axis, specific, frame);
            this.attitude = attitudeOf(axis, specific / frame.gravity());
        } else {
            this.spin.zero();
            Vec3 feedForward = Vec3.ZERO;
            if (desired != null) {
                feedForward = this.commandedFeedForward;
                vehicle.setYRot(vehicle.getYRot()
                        + (float) Mth.clamp(Mth.wrapDegrees(this.commandedYaw - vehicle.getYRot()), -this.yawRate, this.yawRate));
            } else {
                desired = this.stick(vehicle);
            }
            Multirotor.Step step = Multirotor.step(velocity, this.attitude, desired, feedForward, frame, massFactor);
            this.attitude = step.attitude();
            next = step.velocity();
            pose(vehicle, this.attitude.thrustAxis());
            vehicle.setCollectivePitch((float) Mth.clamp(this.attitude.throttle() * frame.gravity()
                    / frame.maxSpecificThrust(massFactor) * 100.0, 0.0, 100.0));
        }
        // Gravity is Multirotor's: StepBody's substepped gravity on a pre-compensated velocity nets +0.375 G/tick.
        vehicle.setNoGravity(true);
        vehicle.setDeltaMovement(next);
        vehicle.xRotSpeed = 0;
        vehicle.yRotSpeed = 0;
        vehicle.zRotSpeed = 0;
    }

    /**
     * Unbalanced rotors at the last collective: body spun by their torque, thrust along the tilted disc.
     * Angular velocity capped at {@link #TUMBLE_MAX} rad/tick, air-damped {@link #TUMBLE_DAMPING}/tick.
     */
    private Vec3 tumble(RotaryWingVehicle vehicle, RotorLayout rotors, double power, Vec3 velocity, double massFactor) {
        double throttle = power * vehicle.getCollectivePitch() / 100.0;
        Vector3f alpha = rotors.torque(throttle, this.thrust, vehicle.physicsEngine.mass);
        double dt2 = 1.0 / 400.0;
        this.spin.mul(TUMBLE_DAMPING).add((float) (alpha.x * dt2), (float) (alpha.y * dt2), (float) (alpha.z * dt2));
        if (this.spin.length() > TUMBLE_MAX) {
            this.spin.normalize(TUMBLE_MAX);
        }
        Quaternionf rot = vehicle.rotYXZ();
        if (this.spin.lengthSquared() > 0) {
            rot.rotateAxis(this.spin.length(), new Vector3f(this.spin).normalize());
        }
        FpvFlight.setAttitude(vehicle, rot);
        Airframe frame = this.airframe(vehicle, 1.0, true);
        Vec3 axis = vehicle.relativeRotDirection(new Vec3(0, 1, 0), false);
        double specific = rotors.meanOutput(throttle) * frame.maxSpecificThrust(massFactor);
        this.attitude = attitudeOf(axis, specific / frame.gravity());
        return Multirotor.integrate(velocity, axis, specific, frame);
    }

    /** Keys -> velocity setpoint in the heading frame; mouse yaw -> heading. */
    private Vec3 stick(RotaryWingVehicle vehicle) {
        ControlUnit c = vehicle.controlUnit;
        float yawStep;
        if (c.leftYaw || c.rightYaw) {
            yawStep = (float) this.yawRate * ((c.rightYaw ? 1 : 0) - (c.leftYaw ? 1 : 0));
        } else if (c.yRotKeep) {
            yawStep = 0;
        } else {
            yawStep = (float) Mth.clamp(Mth.wrapDegrees(c.yRot - vehicle.getYRot()) * this.yawResponse,
                    -this.yawRate, this.yawRate);
        }
        vehicle.setYRot(vehicle.getYRot() + yawStep);
        double fwd = (c.forward ? 1 : 0) - (c.backward ? 1 : 0);
        double side = (c.right ? 1 : 0) - (c.left ? 1 : 0);
        double up = (c.up ? 1 : 0) - (c.down ? 1 : 0);
        double yaw = Math.toRadians(vehicle.getYRot());
        // MC yaw: forward = (-sin, cos), right = (-cos, -sin).
        double x = -Math.sin(yaw) * fwd - Math.cos(yaw) * side;
        double z = Math.cos(yaw) * fwd - Math.sin(yaw) * side;
        double len = Math.sqrt(x * x + z * z);
        double scale = len > 1.0E-6 ? this.maxSpeed / len : 0.0;
        return new Vec3(x * scale, up * this.climbSpeed, z * scale);
    }

    /**
     * Body pitch/roll putting the disc normal on {@code axis}. rotYXZ = Ry(-yaw) Rx(x) Rz(z):
     * body up = (-sin z, cos z cos x, cos z sin x).
     */
    static void pose(RotaryWingVehicle vehicle, Vec3 axis) {
        double yaw = Math.toRadians(vehicle.getYRot());
        double c = Math.cos(yaw), s = Math.sin(yaw);
        double ux = axis.x * c + axis.z * s;
        double uy = axis.y;
        double uz = -axis.x * s + axis.z * c;
        vehicle.setZRot((float) Math.toDegrees(Math.asin(Mth.clamp(-ux, -1.0, 1.0))));
        vehicle.setXRot((float) Math.toDegrees(Math.atan2(uz, uy)));
    }

    /** Inverse of {@link FlightAttitude#thrustAxis}. */
    public static FlightAttitude attitudeOf(Vec3 axis, double throttle) {
        double tilt = Math.acos(Mth.clamp(axis.y, -1.0, 1.0));
        double sin = Math.sin(tilt);
        double k = sin < 1.0E-9 ? 1.0 : tilt / sin;
        return new FlightAttitude(axis.x * k, axis.z * k, throttle);
    }

    public void save(CompoundTag tag) {
        tag.putBoolean("MultirotorFpv", this.fpv);
    }

    public void load(CompoundTag tag) {
        this.fpv = tag.contains("MultirotorFpv") ? tag.getBoolean("MultirotorFpv") : this.defaultFpv;
    }
}
