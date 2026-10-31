package com.wf.combinedassault.vehicle.entity;

import com.wf.wflib.audio.api.AudioCategory;
import com.wf.wflib.audio.api.EngineSound;
import com.wf.combinedassault.vehicle.audio.VehicleSounds;
import com.wf.combinedassault.vehicle.audio.HullLoop;
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
import com.wf.combinedassault.pack.IAnimationEntity;
import com.wf.combinedassault.pack.IAnimationInstance;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.render.animation.context.WheeledVehicleContext;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;
import com.wf.combinedassault.pack.client.vehicle.WheeledVehicleDisplay;
import com.wf.combinedassault.vehicle.util.ParticleUtil;
import com.wf.combinedassault.dynamics.PhysicsHelper;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.dynamics.PhysicsEngine;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;

import java.util.List;

public class WheeledVehicle extends AbstractVehicle implements IAnimationEntity<WheeledVehicle, WheeledVehicleContext> {

    public static final EntityDataAccessor<Float> FORWARD_SPEED = SynchedEntityData.defineId(WheeledVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> TURN_ANGLE = SynchedEntityData.defineId(WheeledVehicle.class, EntityDataSerializers.FLOAT);
    @OnlyIn(Dist.CLIENT)
    private HullLoop tireSqueal;
    public float brakeForce = 10000f;
    public float forwardForce = 4000f;
    public float backwardForce = 4000f;
    public float maxSpeedForward = 0.5f;
    public float maxSpeedBackward = 0.2f;
    public float turnStep = 0.1f;
    public float maxTurn = 2f;
    public float trackSize = 0.1f;
    /** Sliding sideways past tyre grip this tick. */
    public boolean loseTraction;
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

        boolean handbrake = controlUnit.up;
        if (handbrake) {
            velocity = velocity.scale(0.98);
            motion = velocity.length();
        }

        float turnAngle = getTurnAngle();
        if (controlUnit.left || controlUnit.right) {
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
        if (controlUnit.forward) {
            if (motion != 0 && angle > 90) {
                propulsiveForce = vehicleDirection.normalize().scale(brakeForce);
            } else {
                if (motion < maxSpeedForward) {
                    propulsiveForce = vehicleDirection.normalize().scale(forwardForce * (powerScale*mobility.speedScale()));
                }
            }
        } else if (controlUnit.backward) {
            if (motion != 0 && angle < 90) {
                propulsiveForce = vehicleDirection.normalize().scale(-brakeForce);
            } else {
                if (motion < maxSpeedBackward) {
                    propulsiveForce = vehicleDirection.normalize().scale(-backwardForce * (powerScale*mobility.speedScale()));
                }
            }
        }

        // Tyre force ~ wheels down: steering and drive through 1 of 8 wheels rolled an M983 over (terrain WEAVE).
        float share = isInWater() && canWade ? 1 : physicsEngine.tractionShare();
        // High-centred (belly on a 1-block summit / stair ridge, one wheel loaded): belly share drives too, else stuck.
        propulsiveForce = propulsiveForce.scale(Math.min(1, share + BELLY_DRIVE * physicsEngine.bellyShare()));
        if (!hasTraction() && !(isInWater() && canWade)) {
            motion *= 0.33f;
            propulsiveForce = Vec3.ZERO;
        }

        float turnStep = (float) Math.toDegrees(Math.atan2(motion * share
                * (turnAngle / maxTurn)
                * (1 - motion / maxSpeedForward * 0.5)
                * (loseTraction ? 0.8 : 1), bodyDepth));
        // Yaw <= grip / speed (v*w <= grip*g); uncapped: m1129 at full lock ~1 g lateral, wheel friction rolled it.
        if (!handbrake && motion > 1.0e-3) {
            float limit = (float) Math.toDegrees(PhysicsEngine.TYRE_GRIP * PhysicsEngine.G * share / motion);
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
        if (hasTraction() && vehicleDirection.lengthSqr() > 1.0E-6 && !towedOnWheels()) {
            Vec3 heading = vehicleDirection.normalize();
            double along = velocity.dot(heading);
            Vec3 sideways = velocity.subtract(heading.scale(along));
            double slide = sideways.length();
            double grip = PhysicsEngine.TYRE_GRIP * PhysicsEngine.G * share * (handbrake ? HANDBRAKE_GRIP : 1);
            loseTraction = slide > grip;
            velocity = heading.scale(along).add(loseTraction ? sideways.scale((slide - grip) / slide) : Vec3.ZERO);
        }

        motion = velocity.length();
        angle = (float) Math.toDegrees(VectorUtil.angleBetween(velocity, vehicleDirection));
        entityData.set(FORWARD_SPEED, (angle < 90 ? 1 : -1) * (float) motion);

        this.setDeltaMovement(new Vec3(velocity.x, velocity.y + gVelocity, velocity.z));
        return Vec3.ZERO;
    }

    @Override
    protected void tickEngineSpeed() {
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
        tireSqueal.tick(this, Vec3.ZERO, velocity.length() > 0.1
                && Math.sin(VectorUtil.angleBetween(velocity, getLookAngle())) > Math.sin(Math.PI / 10), 1);
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
    }

    @Override
    protected void readPrediction(FriendlyByteBuf buf) {
        super.readPrediction(buf);
        setTurnAngle(buf.readFloat());
        entityData.set(FORWARD_SPEED, buf.readFloat());
        loseTraction = buf.readBoolean();
    }

    public float getForwardSpeed() {
        return this.entityData.get(FORWARD_SPEED);
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
