package com.wf.combinedassault.entity.vehicle;

import com.wf.wflib.audio.EngineSound;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.api.animation.IAnimationEntity;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.client.render.animation.context.TrackedVehicleContext;
import com.wf.combinedassault.client.resource.vehicle.TrackedVehicleDisplay;
import com.wf.combinedassault.client.resource.vehicle.VehicleDisplay;
import com.wf.combinedassault.util.ParticleUtil;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class TrackedVehicle extends AbstractVehicle
        implements IAnimationEntity<TrackedVehicle, TrackedVehicleContext> {

    public static final EntityDataAccessor<Float> FORWARD_SPEED = SynchedEntityData.defineId(TrackedVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> TURN_SPEED = SynchedEntityData.defineId(TrackedVehicle.class, EntityDataSerializers.FLOAT);
    public float brakeAcceleration = 0.025f;
    public float forwardAcceleration = 0.01f;
    public float backwardAcceleration = 0.01f;
    public float maxSpeedForward = 0.5f;
    public float maxSpeedBackward = 0.2f;
    public float turnAcceleration = 1f;
    public float maxTurn = 2f;
    public float trackSize = 0.3f;
    public double trackLength;
    private IAnimationInstance<TrackedVehicleContext> animationInstance;

    public TrackedVehicle(EntityType<? extends AbstractVehicle> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Override
    public IAnimationInstance<TrackedVehicleContext> getAnimationInstance() {
        return animationInstance;
    }

    @Override
    public void initDisplayData(VehicleDisplay<?, ?> display) {
        super.initDisplayData(display);
        if (display instanceof TrackedVehicleDisplay trackedVehicleDisplay) {
            this.animationInstance = trackedVehicleDisplay.createAnimationInstance(this);
        }
    }

    @Override
    protected boolean predictable() {
        return true;
    }

    @Override
    protected void writePrediction(FriendlyByteBuf buf) {
        super.writePrediction(buf);
        buf.writeFloat(entityData.get(FORWARD_SPEED));
        buf.writeFloat(entityData.get(TURN_SPEED));
    }

    @Override
    protected void readPrediction(FriendlyByteBuf buf) {
        super.readPrediction(buf);
        entityData.set(FORWARD_SPEED, buf.readFloat());
        entityData.set(TURN_SPEED, buf.readFloat());
    }

    public float getForwardSpeed() {
        return this.entityData.get(FORWARD_SPEED);
    }

    public float getTurnSpeed() {
        return this.entityData.get(TURN_SPEED);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FORWARD_SPEED, 0f);
        builder.define(TURN_SPEED, 0f);
    }

    @Override
    public void shoot(int partUnitIndex, int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator) {
        if (partUnits.get(partUnitIndex) instanceof WeaponUnit weaponUnit) {
            weaponUnit.shoot(weaponIndex, aimContexts, operator);
        }
    }

    @Override
    protected Vec3 tickMove() {
        if (getDriver() == null && !autopilotControl) {
            controlUnit.reset();
        }

        float vt = entityData.get(TURN_SPEED);
        // Forward component only; sideways (a ram, a skid) slides off at tyre grip instead of turning into speed.
        Vec3 heading = new Vec3(getLookAngle().x, 0, getLookAngle().z).normalize();
        Vec3 planar = new Vec3(getDeltaMovement().x, 0, getDeltaMovement().z);
        double along = planar.dot(heading);
        int sig = along > 0 ? 1 : -1;
        float vf = (float) Math.min(Math.abs(along), Math.abs(entityData.get(FORWARD_SPEED))) * sig;
        Vec3 sideways = planar.subtract(heading.scale(along));
        double slide = sideways.length();
        double grip = PhysicsEngine.TYRE_GRIP * PhysicsEngine.G;
        sideways = slide <= grip ? Vec3.ZERO : sideways.scale((slide - grip) / slide);
        if (!hasPower()) {
            entityData.set(FORWARD_SPEED, vf);
            entityData.set(TURN_SPEED, 0f);
            return new Vec3(0, 0, 0);
        }

        // No vehicle declares a mobility part today => always FULL.
        VehicleMobility mobility = VehicleMobility.of(this);
        // Pack accelerations are unladen: cargo divides them, overload strains the engine.
        float load = (float) load();
        float strain = (float) strain();
        float brake = brakeAcceleration / load;
        float turn = turnAcceleration / load;

        if (controlUnit.forward || controlUnit.backward) {
            if (controlUnit.forward) {
                if (vf < 0) {
                    vf += brake;
                } else {
                    vf += forwardAcceleration * strain / load;
                }
            } else {
                if (vf > 0) {
                    vf -= brake;
                } else {
                    vf -= backwardAcceleration * strain / load;
                }
            }
        }
        if (controlUnit.left || controlUnit.right) {
            if (vf < 0 && !controlUnit.backward) {
                vf += brake;
            }
        }
        vf = Mth.clamp(vf, -maxSpeedBackward * mobility.speedScale() * strain,
                maxSpeedForward * mobility.speedScale() * strain);
        entityData.set(FORWARD_SPEED, vf);

        if (controlUnit.left || controlUnit.right) {
            vt += controlUnit.right ? turn : -turn;
            vt = Mth.clamp(vt, -maxTurn * mobility.turnScale(), maxTurn * mobility.turnScale());
        } else {
            if (vt < 0) {
                vt += turn;
                vt = Math.min(vt, 0);
            } else if (vt > 0) {
                vt -= turn;
                vt = Math.max(vt, 0);
            }
        }
        entityData.set(TURN_SPEED, vt);

        if (hasTraction() || (isInWater() && canWade)) {
            if (controlUnit.backward) {
                vt *= -1;
            }
            this.turnBy(vt + Math.abs(vf) / maxSpeedForward * vt / 5);
            if (Math.abs(vt) > 0) {
                vf *= 0.98f;
            }

            // Horizontal: speed is read back from the planar velocity; along the pitched look => vf x (1 - cos pitch)
            // lost per tick (T-90 stalled on a 1:4 stair hill). Steps lift via rigid-step rows at travel x climbGradient.
            Vec3 motion = heading.scale(vf).add(sideways);
            motion = motion.add(0, Math.min(0, getDeltaMovement().y), 0);
            this.setDeltaMovement(motion);
        }
        return new Vec3(0, 0, 0);
    }

    @Override
    protected void tickEngineSpeed() {
        super.tickEngineSpeed();
        float engineSpeed = getEngineSpeed();
        if (controlUnit.forward || controlUnit.backward || controlUnit.left || controlUnit.right) {
            if (hasPower()) {
                Vec3 velocity = getDeltaMovement();
                Vec3 vehicleDirection = getLookAngle();
                float angle = (float) Math.toDegrees(VectorUtil.angleBetween(velocity, vehicleDirection));
                if ((angle < 90 && controlUnit.forward) || (angle > 90 && controlUnit.backward)) {
                    setEngineSpeed(Mth.clamp(engineSpeed + 1, 0, 100));
                } else if (controlUnit.left || controlUnit.right) {
                    setEngineSpeed(Mth.clamp(engineSpeed + 2, 0, 100));
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
    protected void tickParticle() {
        super.tickParticle();
        trackLength += getDeltaMovement().length();
        if (trackLength >= 1) {
            trackLength = 0;
            if (hasSuspension()) {
                ParticleUtil.spawnSuspensionTracks(this, trackSize);
            } else {
                Vec3 trackLeftPos = relativeRotPos(position().add(mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false);
                Vec3 trackRightPos = relativeRotPos(position().add(-mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false);
                ParticleUtil.spawnTracks(level(), trackSize, getYRot(), trackLeftPos, trackRightPos);
            }
        }
        if (getDeltaMovement().horizontalDistanceSqr() > 0.01 || getTurnSpeed() != 0) {
            if (hasSuspension()) {
                ParticleUtil.spawnSuspensionDust(this);
            } else {
                ParticleUtil.spawnTrackDust(this,
                        relativeRotPos(position().add(mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false),
                        relativeRotPos(position().add(-mainCubeOBB.obb().extents().x, 0, -mainCubeOBB.obb().extents().z), false));
            }
        }
        if (hasPower()) {
            double velocity = Math.abs(entityData.get(FORWARD_SPEED)) + Math.abs(entityData.get(TURN_SPEED));
            if (engineParticleTick > (maxSpeedForward * 0.5 - velocity) / maxSpeedForward * 10) {
                ParticleUtil.spawnEngineSmoke(level(), energyInfo.engineParticleOffsets, position(), new Vec3(xo, yo, zo),
                        pos -> relativeRotPos(pos, false), getLookAngle().normalize().scale(-0.2),
                        (int) Math.ceil(velocity / 16 + 1), 20, 0.3f, 0.4f);
                engineParticleTick = 0;
            } else {
                engineParticleTick += 1;
            }
        }
    }

}
