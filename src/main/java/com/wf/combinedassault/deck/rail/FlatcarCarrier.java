package com.wf.combinedassault.deck.rail;

import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.registry.EntityRollingStockDefinition;
import cam72cam.mod.entity.ModdedEntity;
import cam72cam.mod.math.Vec3d;
import com.wf.combinedassault.deck.HarnessCarrier;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.DeckFrame;
import com.wf.wflib.physics.api.DeckSnapshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * IR stock as a {@link HarnessCarrier}. IR entity is a {@code ModdedEntity} wrapping a UMC {@code CustomEntity}
 * => cannot implement {@link HarnessCarrier} directly; this adapts it, only class here naming an IR type.
 * One adapter/entity forever: harness compares carriers by identity, fresh adapter => detach next tick.
 */
public final class FlatcarCarrier implements HarnessCarrier {

    private static final Map<ModdedEntity, FlatcarCarrier> BY_ENTITY =
            Collections.synchronizedMap(new WeakHashMap<>());

    // Weak: strong value->key edge pins WeakHashMap entry + level forever.
    private final WeakReference<ModdedEntity> entity;
    private final UUID uuid;
    private final int id;
    private final List<AbstractVehicle> carried = new CopyOnWriteArrayList<>();

    /** Immutable once published: children on several region threads share one carrier. */
    private record Pose(DeckSnapshot snapshot, double x, double y, double z, float yaw, float pitch, float roll) {
    }

    // Cached on pose, not tick: IR-vs-load tick order unknown; tick-keyed cache would
    // serve a stale pose to whichever runs first.
    private volatile Pose pose = new Pose(new DeckSnapshot(), Double.NaN, Double.NaN, Double.NaN, Float.NaN,
            Float.NaN, Float.NaN);

    private FlatcarCarrier(ModdedEntity entity) {
        this.entity = new WeakReference<>(entity);
        this.uuid = entity.getUUID();
        this.id = entity.getId();
    }

    /** Null: not rolling stock. */
    @Nullable
    public static FlatcarCarrier of(Entity candidate) {
        if (!(candidate instanceof ModdedEntity modded)
                || !(modded.getSelf() instanceof EntityMoveableRollingStock)) {
            return null;
        }
        return BY_ENTITY.computeIfAbsent(modded, FlatcarCarrier::new);
    }

    /** All live adapters; dead+empty entries pruned first. */
    static List<FlatcarCarrier> loaded() {
        synchronized (BY_ENTITY) {
            BY_ENTITY.values().removeIf(carrier -> carrier.isRemoved() && carrier.carried.isEmpty());
            return new ArrayList<>(BY_ENTITY.values());
        }
    }

    /** Repose on change only. Lazy not pushed: IR doesn't know this exists; must be right whichever entity ticks first. */
    private Pose refresh() {
        EntityMoveableRollingStock stock = stock();
        Vec3d at = stock.getPosition();
        float yaw = stock.getRotationYaw();
        float pitch = stock.getRotationPitch();
        float roll = stock.getRotationRoll();
        Pose last = pose;
        if (at.x == last.x && at.y == last.y && at.z == last.z
                && yaw == last.yaw && pitch == last.pitch && roll == last.roll) {
            return last;
        }
        Quaternionf rotation = DeckFrame.fromEulerYXZ(yaw, pitch, roll, new Quaternionf());

        // Downstream reads box count only; zero boxes => treated as not yet posed.
        EntityRollingStockDefinition definition = stock.getDefinition();
        float halfWidth = (float) (definition.getWidth(stock.gauge) / 2.0);
        float halfLength = (float) (definition.getLength(stock.gauge) / 2.0);
        float height = (float) definition.getHeight(stock.gauge);
        DeckSnapshot snapshot = new DeckSnapshot();
        fill(snapshot.boxBuffer(1), halfWidth, height, halfLength);
        fill(snapshot.deckBoxBuffer(1), halfWidth, height, halfLength);
        snapshot.set(rotation, yaw, at.x, at.y, at.z, 1, 1);
        return pose = new Pose(snapshot, at.x, at.y, at.z, yaw, pitch, roll);
    }

    /** {@code boxes[]} layout: min xyz then max xyz, local to pivot. */
    private static void fill(float[] boxes, float halfWidth, float height, float halfLength) {
        boxes[0] = -halfWidth;
        boxes[1] = 0;
        boxes[2] = -halfLength;
        boxes[3] = halfWidth;
        boxes[4] = height;
        boxes[5] = halfLength;
    }

    public EntityMoveableRollingStock stock() {
        return (EntityMoveableRollingStock) Objects.requireNonNull(entity.get()).getSelf();
    }

    @Override
    public DeckSnapshot deckSnapshot() {
        return refresh().snapshot;
    }

    @Override
    public List<AbstractVehicle> harnessedVehicles() {
        return carried;
    }

    /** IR stock centers on itself: pivot = origin. */
    @Override
    public Vec3 carrierCenterOffset() {
        return Vec3.ZERO;
    }

    @Override
    public Level level() {
        return Objects.requireNonNull(entity.get()).level();
    }

    @Override
    public boolean isRemoved() {
        ModdedEntity entity = this.entity.get();
        return entity == null || entity.isRemoved();
    }

    @Override
    @Nullable
    public Entity.RemovalReason getRemovalReason() {
        ModdedEntity entity = this.entity.get();
        return entity == null ? Entity.RemovalReason.DISCARDED : entity.getRemovalReason();
    }

    @Override
    public Vec3 position() {
        Pose p = entity.get() != null ? refresh() : pose;
        return new Vec3(p.x, p.y, p.z);
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }

    // IR replays authoritative per-tick positions, not lerp-toward-target; current pose already server-final. See TickPos.

    @Override
    public double lerpTargetX() {
        return stock().getPosition().x;
    }

    @Override
    public double lerpTargetY() {
        return stock().getPosition().y;
    }

    @Override
    public double lerpTargetZ() {
        return stock().getPosition().z;
    }

    @Override
    public float lerpTargetXRot() {
        return stock().getRotationPitch();
    }

    @Override
    public float lerpTargetYRot() {
        return stock().getRotationYaw();
    }

    @Override
    public float lerpTargetZRot() {
        return stock().getRotationRoll();
    }
}
