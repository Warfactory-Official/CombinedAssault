package com.wf.combinedassault.aircraft;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A shed rotor or wing ({@link WingLoss}): one glTF node subtree of its vehicle's model, drawn at bind pose. Entity at {@link #pivot()};
 * {@link #rotation()} = model -> world about it. Never saved.
 */
public class RotorDebris extends Entity {

    private static final EntityDataAccessor<String> DISPLAY =
            SynchedEntityData.defineId(RotorDebris.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> NODE =
            SynchedEntityData.defineId(RotorDebris.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Vector3f> PIVOT =
            SynchedEntityData.defineId(RotorDebris.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Quaternionf> ROTATION =
            SynchedEntityData.defineId(RotorDebris.class, EntityDataSerializers.QUATERNION);

    private static final double GRAVITY = 0.06;
    private static final double AIR_DRAG = 0.98;
    private static final double GROUND_DRAG = 0.5;
    /** Spin kept per tick on the ground; the disc also eases flat. */
    private static final float GROUND_SPIN = 0.6f;
    private static final float FLATTEN = 0.15f;
    private static final int SETTLE_TICKS = 400;
    private static final int LIFETIME_TICKS = 1200;

    /** World angular velocity, rad/tick. Server only. */
    private final Vector3f spin = new Vector3f();
    /** Disc axis (wing: up), model frame; eased to world vertical on the ground. Server only. */
    private final Vector3f discAxis = new Vector3f(0, 1, 0);
    private int grounded;

    /** Client: last two synced rotations, for interpolation. */
    private final Quaternionf rotationO = new Quaternionf();
    private final Quaternionf rotationNow = new Quaternionf();

    public RotorDebris(EntityType<? extends RotorDebris> type, Level level) {
        super(type, level);
        noCulling = true;
    }

    public RotorDebris(Level level, Vec3 pos, ResourceLocation display, String node, Vector3f pivot,
                       Vector3f discAxis, Quaternionf rotation, Vec3 velocity, Vector3f spin) {
        this(AircraftContent.ROTOR_DEBRIS.get(), level);
        setPos(pos.x, pos.y, pos.z);
        setDeltaMovement(velocity);
        entityData.set(DISPLAY, display.toString());
        entityData.set(NODE, node);
        entityData.set(PIVOT, new Vector3f(pivot));
        entityData.set(ROTATION, new Quaternionf(rotation));
        this.spin.set(spin);
        this.discAxis.set(discAxis);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DISPLAY, "");
        builder.define(NODE, "");
        builder.define(PIVOT, new Vector3f());
        builder.define(ROTATION, new Quaternionf());
    }

    public ResourceLocation display() {
        return ResourceLocation.parse(entityData.get(DISPLAY));
    }

    public String node() {
        return entityData.get(NODE);
    }

    /** Disc centre, model frame. */
    public Vector3f pivot() {
        return entityData.get(PIVOT);
    }

    public Quaternionf rotation() {
        return entityData.get(ROTATION);
    }

    /** Client. */
    public Quaternionf rotation(float partialTick, Quaternionf out) {
        return rotationO.slerp(rotationNow, partialTick, out);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (ROTATION.equals(key) && tickCount == 0) {
            rotationO.set(rotation());
            rotationNow.set(rotation());
        }
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(getDeltaMovement().add(0, -GRAVITY, 0));
        move(MoverType.SELF, getDeltaMovement());
        boolean ground = onGround();
        setDeltaMovement(getDeltaMovement().multiply(ground ? GROUND_DRAG : AIR_DRAG, AIR_DRAG,
                ground ? GROUND_DRAG : AIR_DRAG));
        if (level().isClientSide()) {
            rotationO.set(rotationNow);
            rotationNow.set(rotation());
            return;
        }
        Quaternionf rotation = new Quaternionf(rotation());
        float angle = spin.length();
        if (angle > 1.0e-4f) {
            rotation.premul(new Quaternionf().rotationAxis(angle, spin.x / angle, spin.y / angle, spin.z / angle));
        }
        if (ground) {
            grounded++;
            spin.mul(GROUND_SPIN);
            rotation.premul(new Quaternionf().slerp(flatten(rotation, discAxis), FLATTEN));
        }
        entityData.set(ROTATION, rotation.normalize());
        if (grounded > SETTLE_TICKS || tickCount > LIFETIME_TICKS) {
            discard();
        }
    }

    /** World rotation taking the disc axis to world up (or down, whichever is nearer). */
    private static Quaternionf flatten(Quaternionf rotation, Vector3f discAxis) {
        Vector3f up = rotation.transform(new Vector3f(discAxis));
        if (up.y < 0) {
            up.negate();
        }
        return new Quaternionf().rotationTo(up, new Vector3f(0, 1, 0));
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
