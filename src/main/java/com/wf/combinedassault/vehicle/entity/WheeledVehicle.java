package com.wf.combinedassault.vehicle.entity;

import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.dynamics.PhysicsEngine;
import com.wf.combinedassault.dynamics.PhysicsHelper;
import com.wf.combinedassault.pack.IAnimationEntity;
import com.wf.combinedassault.pack.IAnimationInstance;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;
import com.wf.combinedassault.pack.client.vehicle.WheeledVehicleDisplay;
import com.wf.combinedassault.render.animation.context.WheeledVehicleContext;
import com.wf.combinedassault.vehicle.audio.HullLoop;
import com.wf.combinedassault.vehicle.audio.VehicleSounds;
import com.wf.combinedassault.vehicle.drive.WheelSpin;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.util.ParticleUtil;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.wflib.audio.api.AudioCategory;
import com.wf.wflib.audio.api.EngineSound;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WheeledVehicle extends GroundVehicle implements IAnimationEntity<WheeledVehicle, WheeledVehicleContext> {

    public static final EntityDataAccessor<Float> FORWARD_SPEED = SynchedEntityData.defineId(WheeledVehicle.class, EntityDataSerializers.FLOAT);
    /** +-1 while spinning (sign = drive direction along the heading), else 0. */
    public static final EntityDataAccessor<Float> WHEEL_SPIN = SynchedEntityData.defineId(WheeledVehicle.class, EntityDataSerializers.FLOAT);
    /** Sliding well past the grip limit (cornering at the limit does not count): tyre squeal. */
    public static final EntityDataAccessor<Boolean> SLIDE = SynchedEntityData.defineId(WheeledVehicle.class, EntityDataSerializers.BOOLEAN);
    private static final double SLIDE_MARGIN = 1.5;
    /** Blocks/tick the wheel surface runs past the ground while spinning. */
    private static final float SPIN_SPEED = 0.4F;
    public static final EntityDataAccessor<Float> TURN_ANGLE = SynchedEntityData.defineId(WheeledVehicle.class, EntityDataSerializers.FLOAT);
    @OnlyIn(Dist.CLIENT)
    private HullLoop tireSqueal;
    public float brakeForce = 10000f;
    public float forwardForce = 4000f;
    public float backwardForce = 4000f;
    public float maxSpeedForward = 0.5f;
    public float maxSpeedBackward = 0.2f;
    public float steerFadeSpeed = 0.5f;
    public float turnStep = 0.1f;
    public float maxTurn = 2f;
    public float trackSize = 0.1f;
    /** Sliding sideways past tyre grip this tick. */
    public boolean loseTraction;
    @Nullable
    public WheelSpin wheelspin;
    /** Drive past grip; hysteresis state, in the prediction snapshot. */
    private boolean spinning;
    /** Max ticks between handbrake presses that make a double tap. */
    private static final int DOUBLE_TAP_TICKS = 8;
    /** Handbrake held on by a double tap; in the prediction snapshot with {@link #handbrakeHeld} and {@link #sinceTap}. */
    private boolean handbrakeLatched;
    private boolean handbrakeHeld;
    private int sinceTap = DOUBLE_TAP_TICKS + 1;
    /** Mouse wheel: lock slew in {@link #turnStep}s per tick; the client already ramps keyed input at 1. */
    private static final float WHEEL_SLEW = 4;
    /** Grip fraction with the handbrake on. */
    private static final double HANDBRAKE_GRIP = 0.3;
    /** Drive per unit belly share (frictionless belly): 1 = as if the wheels carried it. */
    private static final float BELLY_DRIVE = 0.5F;
    public float turnAngle;
    public float turnAngleO;
    public float wheelRotation;
    public double trackLength;
    public long lastRenderTime;
    private IAnimationInstance<WheeledVehicleContext> animationInstance;

    public WheeledVehicle(EntityType<? extends AbstractVehicle> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Override
    public IAnimationInstance<WheeledVehicleContext> getAnimationInstance() {
        return animationInstance;
    }

    @Override
    public void initDisplayData(VehicleDisplay<?, ?> display) {
        super.initDisplayData(display);
        if (display instanceof WheeledVehicleDisplay wheeledVehicleDisplay) {
            this.animationInstance = wheeledVehicleDisplay.createAnimationInstance(this);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FORWARD_SPEED, 0f);
        builder.define(WHEEL_SPIN, 0f);
        builder.define(SLIDE, false);
        builder.define(TURN_ANGLE, 0f);
    }

    @Override
    public void shoot(int partUnitIndex, int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator) {
        if (partUnits.get(partUnitIndex) instanceof WeaponUnit weaponUnit) {
            weaponUnit.shoot(weaponIndex, aimContexts, operator);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            tickInput();
        }
    }

    @Override
    protected boolean controlsNeutral() {
        return super.controlsNeutral() && getTurnAngle() == 0;
    }

    private void tickInput() {
        turnAngleO = turnAngle;
        if (level().isClientSide()) {
            turnAngle = getTurnAngle();
        }
    }

    @Override
    protected Vec3 tickMove() {
        if (getDriver() == null && !autopilotControl) {
            controlUnit.reset();
        }


        double powerScale = getPower() / 100 * strain();
        Vec3 velocity = getDeltaMovement();
        double gVelocity = velocity.y;
        velocity = new Vec3(velocity.x, 0, velocity.z);
        Vec3 vehicleDirection = getLookAngle();
        double motion = velocity.length();
        float angle = (float) Math.toDegrees(VectorUtil.angleBetween(velocity, vehicleDirection));
        vehicleDirection = new Vec3(vehicleDirection.x, 0, vehicleDirection.z);
        Vec3 turnDirection = new Vec3(-vehicleDirection.z, 0, vehicleDirection.x);

        VehicleMobility mobility = VehicleMobility.of(this);

        if (controlUnit.up && !handbrakeHeld) {
            if (sinceTap <= DOUBLE_TAP_TICKS) {
                handbrakeLatched = !handbrakeLatched;
                sinceTap = DOUBLE_TAP_TICKS + 1;
            } else {
                sinceTap = 0;
            }
        } else if (sinceTap <= DOUBLE_TAP_TICKS) {
            sinceTap++;
        }
        handbrakeHeld = controlUnit.up;
        boolean handbrake = controlUnit.up || handbrakeLatched;
        if (handbrake && wheelspin == null) {
            velocity = velocity.scale(0.98);
            motion = velocity.length();
        }

        float turnAngle = getTurnAngle();
        if (controlUnit.wheelSteer) {
            float slew = turnStep * WHEEL_SLEW;
            turnAngle += Mth.clamp(controlUnit.wheel * maxTurn - turnAngle, -slew, slew);
        } else if (controlUnit.left || controlUnit.right) {
            turnAngle += controlUnit.right ? turnStep : -turnStep;
            turnAngle = Mth.clamp(turnAngle, -maxTurn, maxTurn);
        } else {
            if (turnAngle < 0) {
                turnAngle += turnStep;
                turnAngle = Math.min(turnAngle, 0);
            } else if (turnAngle > 0) {
                turnAngle -= turnStep;
                turnAngle = Math.max(turnAngle, 0);
            }
        }
        setTurnAngle(turnAngle);
        if (angle > 90) {
            turnAngle *= -1;
        }

        Vec3 propulsiveForce = Vec3.ZERO;
        if (transmission != null) {
            Vec3 heading = vehicleDirection.normalize();
            double along = velocity.dot(heading);
            drivetrain(transmission, along, maxSpeedForward, maxSpeedBackward, handbrake);
            propulsiveForce = heading.scale(transmissionForce(along, powerScale * mobility.speedScale()));
        } else if (controlUnit.forward) {
            if (motion != 0 && angle > 90) {
                propulsiveForce = vehicleDirection.normalize().scale(brakeForce);
            } else {
                if (motion < maxSpeedForward) {
                    propulsiveForce = vehicleDirection.normalize().scale(powerLimited(forwardForce * (powerScale*mobility.speedScale()), motion));
                }
            }
        } else if (controlUnit.backward) {
            if (motion != 0 && angle < 90) {
                propulsiveForce = vehicleDirection.normalize().scale(-brakeForce);
            } else {
                if (motion < maxSpeedBackward) {
                    propulsiveForce = vehicleDirection.normalize().scale(-powerLimited(backwardForce * (powerScale*mobility.speedScale()), motion));
                }
            }
        }
        if (transmission == null) {
            setPedals((controlUnit.forward ? THROTTLE : 0) | (controlUnit.backward ? BRAKE : 0) | (handbrake ? HANDBRAKE : 0));
        }

        // Tyre force ~ wheels down: steering and drive through 1 of 8 wheels rolled an M983 over (terrain WEAVE).
        float share = isInWater() && canWade ? 1 : physicsEngine.tractionShare();
        // High-centred (belly on a 1-block summit / stair ridge, one wheel loaded): belly share drives too, else stuck.
        double driveScale = Math.min(1, share + BELLY_DRIVE * physicsEngine.bellyShare());
        double circle = 1;
        float spinDir = 0;
        double lockedLoad = 0;
        if (wheelspin != null) {
            // Wheel-borne part capped at the grip budget of the driven wheels' load; belly part outside it.
            double[] loads = wheelLoads();
            lockedLoad = loads[1];
            double wheelScale = Math.min(1, share);
            double demand = propulsiveForce.length() * PhysicsHelper.accelerationPerTick(1, physicsEngine.mass) * wheelScale;
            // Handbrake: drive through locked wheels is lost, only the free driven wheels push.
            double free = handbrake ? loads[0] - loads[2] : loads[0];
            double through = loads[0] > 0 ? free / loads[0] : 0;
            double budget = wheelspin.driveGrip * PhysicsEngine.G * share * free;
            double along = velocity.dot(vehicleDirection);
            // Braking against the motion is not wheelspin.
            boolean driving = propulsiveForce.dot(vehicleDirection) * along >= 0;
            spinning = wheelspin.spins(spinning, demand * through, budget) && driving;
            double delivered = wheelspin.delivered(spinning, demand * through, budget);
            // Locked wheel turns only when its share of the drive beats the brake's hold per unit load.
            boolean lockedSpin = handbrake && loads[2] > 0 && driving
                    && demand / loads[0] > wheelspin.handbrakeHold * PhysicsEngine.G * share;
            if (spinning || lockedSpin) {
                spinDir = (float) Math.signum(propulsiveForce.dot(vehicleDirection));
                double lateralBudget = PhysicsEngine.TYRE_GRIP * PhysicsEngine.G * share * (handbrake ? HANDBRAKE_GRIP : 1);
                double used = lateralBudget > 0 ? delivered / lateralBudget : 1;
                circle = Math.sqrt(Math.max(0, 1 - used * used));
            }
            if (demand > 0 && (spinning || handbrake)) {
                driveScale -= wheelScale * (1 - delivered / demand);
            }
        }
        entityData.set(WHEEL_SPIN, spinDir);
        propulsiveForce = propulsiveForce.scale(driveScale);
        if (!hasTraction() && !(isInWater() && canWade)) {
            motion *= 0.33f;
            propulsiveForce = Vec3.ZERO;
            spinning = false;
            entityData.set(WHEEL_SPIN, 0f);
        }

        float turnStep = (float) Math.toDegrees(Math.atan2(motion * share
                * (turnAngle / maxTurn)
                * (1 - Math.min(motion / steerFadeSpeed, 1) * 0.5)
                * (loseTraction ? 0.8 : 1) * circle, bodyDepth));
        // Yaw <= grip / speed (v*w <= grip*g); uncapped: m1129 at full lock ~1 g lateral, wheel friction rolled it.
        if (!handbrake && motion > 1.0e-3) {
            float limit = (float) Math.toDegrees(PhysicsEngine.TYRE_GRIP * PhysicsEngine.G * share * circle / motion);
            turnStep = Mth.clamp(turnStep, -limit, limit);
        }
        turnStep = this.turnBy(turnStep);
        Vec3 turnAcceleration = turnDirection.normalize().scale(turnStep / 100);
        Vec3 deltaVelocity = propulsiveForce.scale(PhysicsHelper.accelerationPerTick(1, physicsEngine.mass)).add(turnAcceleration);
        velocity = velocity.add(deltaVelocity);
        motion = velocity.length();
        angle = (float) Math.toDegrees(VectorUtil.angleBetween(velocity, vehicleDirection));
        // Tyre grip: sideways speed (vs heading) bleeds off at <= grip per tick, the rest slides; forward kept.
        // Upstream redirects all of it into the heading => a side shove became forward speed, a ram only spun the hull.
        // Towed: the step's axle row grips at the axle; grip at the COM here bled off every turn the hitch asked for.
        boolean slid = false;
        if (hasTraction() && vehicleDirection.lengthSqr() > 1.0E-6 && !towedOnWheels()) {
            Vec3 heading = vehicleDirection.normalize();
            double along = velocity.dot(heading);
            Vec3 sideways = velocity.subtract(heading.scale(along));
            double slide = sideways.length();
            double grip = PhysicsEngine.TYRE_GRIP * PhysicsEngine.G * share * circle * (handbrake ? HANDBRAKE_GRIP : 1);
            loseTraction = slide > grip;
            slid = slide > grip * SLIDE_MARGIN;
            velocity = heading.scale(along).add(loseTraction ? sideways.scale((slide - grip) / slide) : Vec3.ZERO);
        }

        entityData.set(SLIDE, slid);
        if (handbrake && wheelspin != null) {
            // Locked wheels: friction along the motion, held wheels never push back.
            double hold = wheelspin.handbrakeHold * PhysicsEngine.G * share * lockedLoad;
            double speed = velocity.length();
            velocity = speed > hold ? velocity.scale((speed - hold) / speed) : Vec3.ZERO;
        }

        motion = velocity.length();
        angle = (float) Math.toDegrees(VectorUtil.angleBetween(velocity, vehicleDirection));
        entityData.set(FORWARD_SPEED, (angle < 90 ? 1 : -1) * (float) motion);

        this.setDeltaMovement(new Vec3(velocity.x, velocity.y + gVelocity, velocity.z));
        return Vec3.ZERO;
    }

    /**
     * Spring-load fractions {driven, locked, driven and locked}; wheels with no flag set true on any wheel all count.
     * Load = spring force; no load at all => unit counts.
     */
    public double[] wheelLoads() {
        boolean anyDriven = false;
        boolean anyLocked = false;
        for (SuspensionUnit<?> unit : suspensionUnits) {
            if (unit.isLeg()) {
                continue;
            }
            anyDriven |= Boolean.TRUE.equals(unit.driven());
            anyLocked |= Boolean.TRUE.equals(unit.locked());
        }
        double total = 0;
        double[] sum = new double[3];
        for (int pass = 0; pass < 2; pass++) {
            for (SuspensionUnit<?> unit : suspensionUnits) {
                if (unit.isLeg() || !unit.isActive()) {
                    continue;
                }
                double weight = pass == 0 ? unit.load() : 1;
                boolean driven = !anyDriven || Boolean.TRUE.equals(unit.driven());
                boolean locked = !anyLocked || Boolean.TRUE.equals(unit.locked());
                total += weight;
                sum[0] += driven ? weight : 0;
                sum[1] += locked ? weight : 0;
                sum[2] += driven && locked ? weight : 0;
            }
            if (total > 0) {
                break;
            }
        }
        for (int i = 0; i < 3; i++) {
            sum[i] = total > 0 ? sum[i] / total : 0;
        }
        return sum;
    }

    /** {@link #drivetrain}'s command as force along the heading. */
    private double transmissionForce(double along, double driveScale) {
        float perForce = (float) PhysicsHelper.accelerationPerTick(1, physicsEngine.mass);
        if (braking || engineBraking) {
            return -Math.signum(along) * Math.min(brakeForce * (braking ? 1 : ENGINE_BRAKE), Math.abs(along) / perForce);
        }
        if (push != 0 && push * along < pushCap) {
            return Math.signum(push) * powerLimited(Math.abs(push) * (push > 0 ? forwardForce * pushScale : backwardForce) * driveScale, along);
        }
        return 0;
    }

    @Override
    protected void tickEngineSpeed() {
        if (transmission != null) {
            return;
        }
        super.tickEngineSpeed();
        float engineSpeed = getEngineSpeed();
        if (controlUnit.forward || controlUnit.backward) {
            if (hasPower()) {
                Vec3 velocity = getDeltaMovement();
                Vec3 vehicleDirection = getLookAngle();
                float angle = (float) Math.toDegrees(VectorUtil.angleBetween(velocity, vehicleDirection));
                if ((angle < 90 && controlUnit.forward) || (angle > 90 && controlUnit.backward)) {
                    setEngineSpeed(Mth.clamp(engineSpeed + 1, 0, 100));
                } else {
                    setEngineSpeed(Mth.clamp(engineSpeed - 2, 0, 100));
                }
            }
        } else {
            if (engineSpeed > 60) {
                setEngineSpeed(Mth.clamp(engineSpeed - 2, 0, 100));
            }
        }
    }

    @Override
    public float audioInput(EngineSound.Input input) {
        if (input == EngineSound.Input.SPEED) {
            return (float) (Math.abs(getDeltaMovement().dot(new Vec3(axes()[2]))) / maxSpeedForward);
        }
        return super.audioInput(input);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected void tickSound() {
        super.tickSound();
        if (tireSqueal == null) {
            tireSqueal = new HullLoop(VehicleSounds.TIRE_SQUEAL, AudioCategory.VEHICLES, 0.05F);
        }
        Vec3 velocity = new Vec3(getDeltaMovement().x, 0, getDeltaMovement().z);
        boolean locked = (getPedals() & HANDBRAKE) != 0 && wheelspin != null;
        tireSqueal.tick(this, Vec3.ZERO, entityData.get(WHEEL_SPIN) != 0
                || velocity.length() > 0.1 && (entityData.get(SLIDE) || locked), 1);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected void tickParticle() {
        super.tickParticle();
        trackLength += getDeltaMovement().length();
        if (trackLength >= 0.5) {
            trackLength = 0;
            if (hasSuspension()) {
                ParticleUtil.spawnSuspensionTracks(this, trackSize);
            } else {
                Vec3 trackLeftPos = relativeRotPos(position().add(mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false);
                Vec3 trackRightPos = relativeRotPos(position().add(-mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false);
                ParticleUtil.spawnTracks(level(), trackSize, getYRot(), trackLeftPos, trackRightPos);
            }
        }
        if (getDeltaMovement().horizontalDistanceSqr() > 0.01) {
            if (hasSuspension()) {
                ParticleUtil.spawnSuspensionDust(this);
            } else {
                ParticleUtil.spawnTrackDust(this,
                        relativeRotPos(position().add(mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false),
                        relativeRotPos(position().add(-mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false));
            }
        }
        if (hasPower()) {
            double velocity = Math.abs(entityData.get(FORWARD_SPEED));
            if (engineParticleTick > (maxSpeedForward * 0.5 - velocity) / maxSpeedForward * 10) {
                ParticleUtil.spawnEngineSmoke(level(), energyInfo.engineParticleOffsets, position(), new Vec3(xo, yo, zo),
                        pos -> relativeRotPos(pos, false), getLookAngle().normalize().scale(-0.1),
                        (int) Math.ceil(velocity / 16 + 1), 10, 0.2f, 0.3f);
                engineParticleTick = 0;
            } else {
                engineParticleTick += 1;
            }
        }
    }

    @Override
    protected boolean predictable() {
        return true;
    }

    @Override
    protected void writePrediction(FriendlyByteBuf buf) {
        super.writePrediction(buf);
        buf.writeFloat(entityData.get(TURN_ANGLE));
        buf.writeFloat(entityData.get(FORWARD_SPEED));
        buf.writeBoolean(loseTraction);
        buf.writeBoolean(spinning);
        buf.writeBoolean(handbrakeLatched);
        buf.writeBoolean(handbrakeHeld);
        buf.writeVarInt(sinceTap);
        buf.writeFloat(entityData.get(WHEEL_SPIN));
    }

    @Override
    protected void readPrediction(FriendlyByteBuf buf) {
        super.readPrediction(buf);
        setTurnAngle(buf.readFloat());
        entityData.set(FORWARD_SPEED, buf.readFloat());
        loseTraction = buf.readBoolean();
        spinning = buf.readBoolean();
        handbrakeLatched = buf.readBoolean();
        handbrakeHeld = buf.readBoolean();
        sinceTap = buf.readVarInt();
        entityData.set(WHEEL_SPIN, buf.readFloat());
    }

    /** Blocks/tick of the wheel surface; exceeds ground speed while spinning. */
    public float getWheelSpeed() {
        return getForwardSpeed() + entityData.get(WHEEL_SPIN) * SPIN_SPEED;
    }

    @Override
    public float getForwardSpeed() {
        return this.entityData.get(FORWARD_SPEED);
    }

    /** Half-track: belts follow the ground, yaw rate measured (front wheels steer, tracks only roll). */
    @Override
    public double trackSpeed(double lateral) {
        return getForwardSpeed() * 20.0 + Math.toRadians(Mth.wrapDegrees(getYRot() - yRotO)) * 20.0 * lateral;
    }

    public float getTurnAngle() {
        return this.entityData.get(TURN_ANGLE);
    }

    public void setTurnAngle(float value) {
        turnAngle = value;
        this.entityData.set(TURN_ANGLE, value);
    }

    public float getSpeed() {
        return (float) getDeltaMovement().length();
    }

}
