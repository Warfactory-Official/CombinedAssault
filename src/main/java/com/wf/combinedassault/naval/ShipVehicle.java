package com.wf.combinedassault.naval;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.pack.IAnimationEntity;
import com.wf.combinedassault.pack.IAnimationInstance;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.naval.client.ShipVehicleContext;
import com.wf.combinedassault.naval.client.ShipVehicleDisplay;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;
import com.wf.combinedassault.naval.trial.TrialProfile;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.List;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

/**
 * A surface ship or a submarine: a hull held up by the water rather than by contacts.
 *
 * <p>Motion: {@link ShipHydrodynamics}, fed by {@link SeaProbe}; afloat => no step gravity. Seabed, banks: ordinary
 * contacts.
 */
public class ShipVehicle extends AbstractVehicle
        implements IAnimationEntity<ShipVehicle, ShipVehicleContext> {

    public static final EntityDataAccessor<Float> THROTTLE = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> RUDDER = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> SURGE = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> KEEL_CLEARANCE = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DEPTH = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DEPTH_SETTING = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Boolean> AFLOAT = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> SHALLOW = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> AGROUND = SynchedEntityData.defineId(ShipVehicle.class, EntityDataSerializers.BOOLEAN);

    /** Ticks between crush-depth hits. */
    private static final int CRUSH_INTERVAL = 20;
    /** Share of max health a crush hit takes, per interval. */
    private static final float CRUSH_DAMAGE = 0.02f;
    /** Smoothing of the tick-cost readout. */
    private static final double COST_SMOOTHING = 0.05;
    private static final double STUCK_SMOOTHING = 0.2;
    private static final double STUCK_MIN_SPEED = 0.02;
    private static final double STUCK_FRACTION = 0.25;
    /** Ticks a block reading persists after the last tick that showed it. */
    private static final int BLOCKED_HOLD = 10;

    private ShipHydrodynamics.Params params = new ShipHydrodynamics.Params();
    private List<Propeller> propellers = List.of();
    private final ShipHydrodynamics model = new ShipHydrodynamics();
    private final SeaProbe probe = new SeaProbe();
    private final ShipHydrodynamics.Controls controls = new ShipHydrodynamics.Controls();
    private final ShipHydrodynamics.Pose pose = new ShipHydrodynamics.Pose();
    private ShipHydrodynamics.Environment environment = new ShipHydrodynamics.Environment();
    private IAnimationInstance<ShipVehicleContext> animationInstance;
    /** Server tick cost, exponentially smoothed, for the naval status readout. */
    private double tickCostNanos;
    /** The last tick's cost unsmoothed, which is what a trial samples: a spike is the point of it. */
    private long lastTickNanos;
    private int groundedTicks;
    /** Smoothed horizontal speed the hull asked for and the speed it actually made, for stuck detection. */
    private double intendedSpeed;
    private double realisedSpeed;
    private double lastX = Double.NaN, lastZ;
    private int blockedTicks;
    /** Whether the world refused this tick's motion, which is half of what "aground" means. */
    private boolean blocked;
    /** Throttle sign when blocking last began. */
    private double pinnedThrottle;

    public ShipVehicle(EntityType<? extends AbstractVehicle> type, Level level) {
        super(type, level);
        // No free-flight wobble and no tumbling: attitude is the model's.
        this.physicsEngine.lockCenterRot = true;
    }


    public void setParams(ShipHydrodynamics.Params params) {
        this.params = params;
    }

    public void setPropellers(List<Propeller> propellers) {
        this.propellers = propellers;
    }

    public List<Propeller> propellers() {
        return propellers;
    }

    public ShipHydrodynamics.Params params() {
        return params;
    }

    public ShipHydrodynamics model() {
        return model;
    }

    public ShipHydrodynamics.Environment environment() {
        return environment;
    }

    public double tickCostNanos() {
        return tickCostNanos;
    }

    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** The world is refusing the hull's motion: a contact, a bank, or nothing it can push against. */
    public boolean blocked() {
        return blocked;
    }

    public boolean isSubmarine() {
        return params.submarine;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(THROTTLE, 0f);
        builder.define(RUDDER, 0f);
        builder.define(SURGE, 0f);
        builder.define(KEEL_CLEARANCE, Float.NaN);
        builder.define(DEPTH, 0f);
        builder.define(DEPTH_SETTING, 0f);
        builder.define(AFLOAT, false);
        builder.define(SHALLOW, false);
        builder.define(AGROUND, false);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putDouble("Throttle", model.throttle);
        compound.putDouble("Rudder", model.rudder);
        compound.putDouble("DepthSetting", model.depthSetting);
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        model.throttle = compound.getDouble("Throttle");
        model.rudder = compound.getDouble("Rudder");
        model.depthSetting = compound.getDouble("DepthSetting");
    }

    @Override
    public void tick() {
        boolean server = !level().isClientSide();
        long start = server ? System.nanoTime() : 0;
        super.tick();
        if (server) {
            lastTickNanos = System.nanoTime() - start;
            tickCostNanos += (lastTickNanos - tickCostNanos) * COST_SMOOTHING;
        }
    }

    @Override
    public void shoot(int partUnitIndex, int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator) {
        if (partUnits.get(partUnitIndex) instanceof WeaponUnit weaponUnit) {
            weaponUnit.shoot(weaponIndex, aimContexts, operator);
        }
    }

    @Override
    public IAnimationInstance<ShipVehicleContext> getAnimationInstance() {
        return animationInstance;
    }

    @Override
    public void initDisplayData(VehicleDisplay<?, ?> display) {
        super.initDisplayData(display);
        if (display instanceof ShipVehicleDisplay shipDisplay) {
            this.animationInstance = shipDisplay.createAnimationInstance(this);
        }
    }

    /** A ship climbs nothing: a rising seabed is a grounding, not a step. */
    @Override
    public float maxUpStep() {
        return 0.0F;
    }

    @Override
    protected Vec3 tickMove() {
        if (getDriver() == null) {
            controlUnit.reset();
        }
        VehicleCubeOBB hull = getMainCubeOBB();
        if (hull == null) {
            return Vec3.ZERO;
        }
        double halfBeam = hull.obb().extents().x;
        double halfLength = hull.obb().extents().z;
        double waterlineY = getY() + params.waterline;
        double keelY = waterlineY - params.draft;
        long probeAt = TrialProfile.start();
        environment = probe.probe(level(), getX(), getZ(), getYRot(), halfLength, halfBeam, waterlineY, keelY);
        TrialProfile.stop(TrialProfile.Stage.SEA, probeAt);

        Vec3 asked = deltaMovementO == null ? Vec3.ZERO : deltaMovementO;
        double askedSpeed = Math.sqrt(asked.x * asked.x + asked.z * asked.z);
        double made = Double.isNaN(lastX) ? askedSpeed
                : Math.sqrt((getX() - lastX) * (getX() - lastX) + (getZ() - lastZ) * (getZ() - lastZ));
        lastX = getX();
        lastZ = getZ();
        intendedSpeed += (askedSpeed - intendedSpeed) * STUCK_SMOOTHING;
        realisedSpeed += (made - realisedSpeed) * STUCK_SMOOTHING;
        boolean stuck = (intendedSpeed > STUCK_MIN_SPEED && realisedSpeed < STUCK_FRACTION * intendedSpeed)
                // Supported: heave presses the bow into a shelf edge => jam every tick, any throttle.
                || physicsEngine.blockedByContacts && !onGround();
        // Blocked hull stops pushing; hold the reading while throttle stays set and speed stays near zero.
        // Reversal releases; held => astern surge 0, grounded bow never backs off.
        boolean stillPinned = blockedTicks > 0 && Math.abs(model.throttle) > 0.05
                && Math.signum(model.throttle) == pinnedThrottle && realisedSpeed < STUCK_MIN_SPEED * 0.25;
        if (stuck) {
            pinnedThrottle = Math.signum(model.throttle);
        }
        blockedTicks = stuck || stillPinned ? BLOCKED_HOLD : Math.max(0, blockedTicks - 1);
        blocked = blockedTicks > 0;

        VehicleMobility mobility = VehicleMobility.of(this);
        controls.blocked = blocked;
        controls.supported = onGround();
        controls.ahead = controlUnit.forward;
        controls.astern = controlUnit.backward;
        controls.port = controlUnit.left;
        controls.starboard = controlUnit.right;
        controls.dive = controlUnit.down;
        controls.rise = controlUnit.up;
        controls.power = hasPower() ? getPower() / 100.0 * strain() : 0.0;
        controls.speedScale = mobility.speedScale();
        controls.turnScale = mobility.turnScale();
        if (!hasPower()) {
            model.throttle = ShipHydrodynamics.approach(model.throttle, 0, params.throttleRate / 20.0);
        }

        Vec3 velocity = getDeltaMovement();
        pose.y = getY();
        pose.yaw = getYRot();
        pose.pitch = getXRot();
        pose.roll = getZRot();
        pose.vx = velocity.x;
        pose.vy = velocity.y;
        pose.vz = velocity.z;
        double maxYaw = Math.toDegrees(physicsEngine.effectiveMaxRotV());
        long stepAt = TrialProfile.start();
        model.step(params, pose, controls, environment, physicsEngine.mass, curbWeight, halfLength * 2,
                maxYaw, degrees -> turnBy((float) degrees));
        TrialProfile.stop(TrialProfile.Stage.SEA, stepAt);
        setDeltaMovement(pose.vx, pose.vy, pose.vz);
        setXRot(pose.pitch);
        setZRot(pose.roll);

        boolean grounded = environment.afloat
                && (blocked || model.turnBlocked
                    || (!Double.isNaN(environment.keelClearance) && environment.keelClearance <= 0));
        groundedTicks = grounded ? Math.min(groundedTicks + 1, 40) : Math.max(groundedTicks - 1, 0);

        entityData.set(THROTTLE, (float) model.throttle);
        entityData.set(RUDDER, (float) model.rudder);
        entityData.set(SURGE, blocked ? 0f : (float) model.surge);
        entityData.set(KEEL_CLEARANCE, (float) environment.keelClearance);
        entityData.set(DEPTH, (float) model.depth);
        entityData.set(DEPTH_SETTING, (float) model.depthSetting);
        entityData.set(AFLOAT, environment.afloat);
        entityData.set(SHALLOW, model.shallow);
        entityData.set(AGROUND, groundedTicks >= 20);

        if (params.submarine) {
            tickSubmarine();
        }
        return Vec3.ZERO;
    }

    @Override
    protected boolean restSupported() {
        return super.restSupported() || environment.afloat;
    }

    /** Afloat: heave is the model's. */
    @Override
    public boolean weightCarried() {
        return environment.afloat;
    }

    private void tickSubmarine() {
        if (model.submerged) {
            for (Entity passenger : getPassengers()) {
                if (passenger instanceof LivingEntity living) {
                    living.setAirSupply(living.getMaxAirSupply());
                }
            }
        }
        if (model.crushed(params) && tickCount % CRUSH_INTERVAL == 0) {
            float damage = Math.max(getMaxHealth() * CRUSH_DAMAGE, 1.0f);
            float health = getHealth() - damage;
            if (health <= 0) {
                hurt(level().damageSources().drown(), getMaxHealth());
            } else {
                setHealth(health);
            }
        }
    }

    @Override
    protected void tickEngineSpeed() {
        float target = hasPower() ? 60 + 40 * (float) Math.abs(model.throttle) : 0;
        float engineSpeed = getEngineSpeed();
        setEngineSpeed(engineSpeed < target ? Math.min(target, engineSpeed + 1) : Math.max(target, engineSpeed - 1));
    }

    /**
     * Vanilla scans every block in the bounding box for fluid twice a tick. At carrier scale that is
     * millions of reads; the probe already knows where the water is.
     */
    @Override
    protected boolean updateInWaterStateAndDoFluidPushing() {
        this.fluidHeight.clear();
        this.forgeFluidTypeHeight.clear();
        boolean wet = entityData.get(AFLOAT);
        if (wet) {
            double surface = level().isClientSide() || Double.isNaN(environment.surfaceY)
                    ? getY() + params.waterline : environment.surfaceY;
            double height = Math.max(0.0, surface - getBoundingBox().minY);
            this.fluidHeight.put(FluidTags.WATER, height);
            setFluidTypeHeight(NeoForgeMod.WATER_TYPE.value(), height);
        }
        this.wasTouchingWater = wet;
        return wet;
    }

    /** Also a bounding-box scan; a hull is never in a portal, a cobweb or a fire. */
    @Override
    protected void checkInsideBlocks() {
    }

    public float throttle() {
        return entityData.get(THROTTLE);
    }

    public float rudder() {
        return entityData.get(RUDDER);
    }

    public float surge() {
        return entityData.get(SURGE);
    }

    public float keelClearance() {
        return entityData.get(KEEL_CLEARANCE);
    }

    public float depth() {
        return entityData.get(DEPTH);
    }

    public float depthSetting() {
        return entityData.get(DEPTH_SETTING);
    }

    public boolean afloat() {
        return entityData.get(AFLOAT);
    }

    public boolean shallow() {
        return entityData.get(SHALLOW);
    }

    public boolean aground() {
        return entityData.get(AGROUND);
    }

    /** Sets the telegraph and rudder directly; for commands and tests. */
    public void setHelm(double throttle, double rudder) {
        model.throttle = Mth.clamp(throttle, -1, 1);
        model.rudder = Mth.clamp(rudder, -1, 1);
        model.rudderHeld = model.rudder != 0;
    }

    public void setDepthSetting(double depth) {
        model.depthSetting = Math.max(0, depth);
    }

}
