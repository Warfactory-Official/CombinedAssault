package com.wf.combinedassault.tow;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Couples vehicles + persists across reload. Towed vehicle holds the link (names its tractor) => train is a chain, walkable front-to-back by {@link TowSolver}. */
public final class TowLinks {

    private static final String TAG_TRACTOR = "TowTractor";
    private static final String TAG_HITCH = "TowHitch";
    private static final String TAG_MODE = "TowMode";
    private static final String TAG_CABLE = "TowCable";
    private static final String TAG_CHUNK = "TowTractorChunk";

    /** No chunk recorded => timeout always runs. */
    private static final long UNKNOWN_CHUNK = Long.MIN_VALUE;

    /** Blocks. */
    public static final double COUPLE_REACH = 3.5;

    /** Retry period, ticks. */
    private static final int RELINK_INTERVAL = 10;

    /** Giveup horizon, ticks; counts only while the tractor's chunk holds loaded entities. */
    private static final int RELINK_TIMEOUT = 20 * 60;

    /** Towed vehicles per level; what the solve iterates. */
    private static final Map<Level, List<AbstractVehicle>> TOWED = new IdentityHashMap<>();

    private TowLinks() {
    }

    /** Waiting for its tractor: from a save load, or the tractor's chunk unloading first. */
    public static final class Pending {

        public final UUID tractor;
        public final String hitch;
        public final TowMode mode;
        public final double cable;
        /** Tractor's chunk as last seen; timeout runs only while it holds loaded entities. */
        final long chunk;
        public int ticks;
        int attempts;

        Pending(UUID tractor, String hitch, TowMode mode, double cable, long chunk) {
            this.tractor = tractor;
            this.hitch = hitch;
            this.mode = mode;
            this.cable = cable;
            this.chunk = chunk;
        }

    }

    // ---------------------------------------------------------------- geometry

    /** Vehicle-frame point -> world; same transform as seat placement (pivot = {@code centerOffset}). */
    public static Vec3 worldPoint(AbstractVehicle vehicle, Vec3 offset) {
        Vector3f rotated = vehicle.rotYXZ().transform(offset.subtract(vehicle.centerOffset).toVector3f());
        return vehicle.position().add(vehicle.centerOffset).add(rotated.x, rotated.y, rotated.z);
    }

    public static Vec3 hitchPoint(AbstractVehicle tractor, HitchInfo hitch) {
        return worldPoint(tractor, hitch.offset);
    }

    public static Vec3 couplingPoint(AbstractVehicle towed) {
        return worldPoint(towed, towed.towing.towable.offset);
    }

    /** Sling eye: top centre of the main hull box, own frame. */
    public static Vec3 slingOffset(AbstractVehicle towed) {
        VehicleCubeOBB main = towed.getMainCubeOBB();
        return main == null ? Vec3.ZERO : main.offset().add(0, main.obb().extents().y, 0);
    }

    /** Where this mode's rope or joint meets the towed vehicle. */
    public static Vec3 attachPoint(AbstractVehicle towed, TowMode mode) {
        return mode == TowMode.SLING ? worldPoint(towed, slingOffset(towed)) : couplingPoint(towed);
    }

    // ---------------------------------------------------------------- state

    @Nullable
    public static TowLink linkOf(AbstractVehicle vehicle) {
        return vehicle.towLink();
    }

    public static boolean isTowed(AbstractVehicle vehicle) {
        return vehicle.towLink() != null;
    }

    @Nullable
    public static AbstractVehicle tractorOf(AbstractVehicle vehicle) {
        TowLink link = vehicle.towLink();
        return link == null ? null : link.tractor();
    }

    /** Direct only; unordered. */
    public static List<AbstractVehicle> towedBy(AbstractVehicle tractor) {
        List<AbstractVehicle> index = TOWED.get(tractor.level());
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        List<AbstractVehicle> out = new ArrayList<>(1);
        for (AbstractVehicle towed : index) {
            if (tractorOf(towed) == tractor) {
                out.add(towed);
            }
        }
        return out;
    }

    /** No allocation; called every tick by the sleep/rest check. */
    public static boolean tows(AbstractVehicle tractor) {
        List<AbstractVehicle> index = TOWED.get(tractor.level());
        if (index != null) {
            for (int i = 0, size = index.size(); i < size; i++) {
                if (tractorOf(index.get(i)) == tractor) {
                    return true;
                }
            }
        }
        return false;
    }

    public static AbstractVehicle head(AbstractVehicle vehicle) {
        AbstractVehicle at = vehicle;
        for (int guard = 0; guard < 16; guard++) {
            AbstractVehicle tractor = tractorOf(at);
            if (tractor == null) {
                return at;
            }
            at = tractor;
        }
        return at;
    }

    public static List<AbstractVehicle> train(AbstractVehicle head) {
        List<AbstractVehicle> out = new ArrayList<>(2);
        collect(head, out);
        return out;
    }

    private static void collect(AbstractVehicle vehicle, List<AbstractVehicle> out) {
        if (out.size() >= 16 || out.contains(vehicle)) {
            return;
        }
        out.add(vehicle);
        for (AbstractVehicle towed : towedBy(vehicle)) {
            collect(towed, out);
        }
    }

    public static float loadBehind(AbstractVehicle tractor) {
        float mass = 0;
        for (AbstractVehicle towed : towedBy(tractor)) {
            mass += towed.physicsEngine.mass + loadBehind(towed);
        }
        return mass;
    }

    /** Tractor+trailer hulls deliberately overlap (fifth-wheel: trailer nose over tractor's rear axle) => same-train pairs must not push each other apart. */
    public static boolean sameTrain(AbstractVehicle one, AbstractVehicle other) {
        return one != other && head(one) == head(other);
    }

    /** Must not sleep: towing something, or towed by a tractor that's moving, airborne, or gone. */
    public static boolean awake(AbstractVehicle vehicle) {
        TowLink link = vehicle.towLink();
        if (link == null) {
            return tows(vehicle);
        }
        AbstractVehicle tractor = link.tractor();
        return tractor.isRemoved() || tractor.getDeltaMovement().lengthSqr() > 1.0E-6
                || !tractor.onGround();
    }

    // ---------------------------------------------------------------- coupling

    /** Why a coupling attempt failed; {@link #NONE} = it didn't. */
    public enum Refusal {
        NONE,
        NOT_TOWABLE,
        WRONG_MODE,
        NOT_ACCEPTED,
        NOT_STOWED,
        TOO_HEAVY,
        OCCUPIED,
        ALREADY_TOWED,
        WOULD_LOOP,
        OUT_OF_REACH
    }

    /** Pure: tests eligibility without coupling. */
    public static Refusal check(AbstractVehicle tractor, AbstractVehicle towed, HitchInfo hitch,
                                TowMode mode, boolean checkReach) {
        TowableInfo towable = towed.towing.towable;
        if (!towable.supports(mode)) {
            return towable.enabled ? Refusal.WRONG_MODE : Refusal.NOT_TOWABLE;
        }
        if (!hitch.supports(mode)) {
            return Refusal.WRONG_MODE;
        }
        if (!hitch.accepts(towed.getVehicleId().toString(), towable.tags)) {
            return Refusal.NOT_ACCEPTED;
        }
        if (!stowed(towed)) {
            return Refusal.NOT_STOWED;
        }
        if (hitch.maxMass > 0 && towed.physicsEngine.mass + loadBehind(towed) > hitch.maxMass) {
            return Refusal.TOO_HEAVY;
        }
        if (isTowed(towed)) {
            return Refusal.ALREADY_TOWED;
        }
        if (occupant(tractor, hitch.id) != null) {
            return Refusal.OCCUPIED;
        }
        if (tractor == towed || head(tractor) == towed || tractor.level() != towed.level()) {
            return Refusal.WOULD_LOOP;
        }
        if (checkReach) {
            double reach = mode.rope() ? hitch.cableLength : COUPLE_REACH;
            if (hitchPoint(tractor, hitch).distanceTo(attachPoint(towed, mode)) > reach) {
                return Refusal.OUT_OF_REACH;
            }
        }
        return Refusal.NONE;
    }

    /** {@code stowed_by} part off (or none). */
    public static boolean stowed(AbstractVehicle towed) {
        String id = towed.towing.towable.stowedBy;
        return id == null || towed.getPartUnit(id).map(p -> p instanceof SwitchableUnit<?> s && !s.isOn()).orElse(true);
    }

    @Nullable
    public static AbstractVehicle occupant(AbstractVehicle tractor, String hitchId) {
        for (AbstractVehicle towed : towedBy(tractor)) {
            TowLink link = towed.towLink();
            if (link != null && link.hitchId().equals(hitchId)) {
                return towed;
            }
        }
        return null;
    }

    /** Hangs towed off tractor's hitch; false => {@link #check} refused it. */
    public static boolean couple(AbstractVehicle tractor, AbstractVehicle towed, HitchInfo hitch,
                                 TowMode mode) {
        if (check(tractor, towed, hitch, mode, false) != Refusal.NONE) {
            return false;
        }
        double cable = mode.rope() ? hitch.cableLength : 0;
        TowLink link = new TowLink(tractor, hitch.id, mode, cable);
        towed.setTowLink(link);
        towed.setPendingTow(null);
        index(towed).add(towed);
        int index = tractor.towing.hitches.indexOf(hitch);
        towed.setTowTractorId(tractor.getId());
        towed.setTowHitch(index < 0 ? -1 : index * TowMode.COUNT + mode.ordinal());
        return true;
    }

    /** No reposition/velocity reset: towed vehicle keeps wherever it is, moving at whatever it had. */
    public static boolean uncouple(AbstractVehicle towed) {
        if (towed.towLink() == null) {
            return false;
        }
        towed.setTowLink(null);
        towed.setTowTractorId(-1);
        towed.setTowHitch(-1);
        List<AbstractVehicle> index = TOWED.get(towed.level());
        if (index != null) {
            index.remove(towed);
        }
        return true;
    }

    /** Direct children only, not the whole train behind them. */
    public static void releaseAll(AbstractVehicle tractor) {
        for (AbstractVehicle towed : towedBy(tractor)) {
            uncouple(towed);
        }
    }

    /** Call when a vehicle leaves the level. */
    public static void detach(AbstractVehicle vehicle) {
        uncouple(vehicle);
        releaseAll(vehicle);
    }

    /** Chunk unload: this vehicle's own link is saved via NBT already; what it tows gets a {@link Pending} to wait for its return. */
    public static void unload(AbstractVehicle vehicle) {
        uncouple(vehicle);
        for (AbstractVehicle towed : towedBy(vehicle)) {
            TowLink link = towed.towLink();
            uncouple(towed);
            towed.setPendingTow(new Pending(vehicle.getUUID(), link.hitchId(), link.mode(), link.cable(),
                    ChunkPos.asLong(vehicle.blockPosition())));
        }
    }

    private static List<AbstractVehicle> index(AbstractVehicle vehicle) {
        return TOWED.computeIfAbsent(vehicle.level(), level -> new ArrayList<>(2));
    }

    public static List<AbstractVehicle> towedIn(Level level) {
        List<AbstractVehicle> towed = TOWED.get(level);
        if (towed == null || towed.isEmpty()) {
            return List.of();
        }
        towed.removeIf(vehicle -> vehicle.isRemoved() || vehicle.level() != level
                || vehicle.towLink() == null);
        return towed;
    }

    public static void forget(Level level) {
        TOWED.remove(level);
    }

    // ---------------------------------------------------------------- persistence

    public static void save(AbstractVehicle vehicle, CompoundTag tag) {
        TowLink link = vehicle.towLink();
        if (link != null) {
            tag.putUUID(TAG_TRACTOR, link.tractor().getUUID());
            tag.putString(TAG_HITCH, link.hitchId());
            tag.putString(TAG_MODE, link.mode().lowerName());
            tag.putDouble(TAG_CABLE, link.cable());
            tag.putLong(TAG_CHUNK, ChunkPos.asLong(link.tractor().blockPosition()));
            return;
        }
        Pending pending = vehicle.pendingTow();
        if (pending != null) {
            tag.putUUID(TAG_TRACTOR, pending.tractor);
            tag.putString(TAG_HITCH, pending.hitch);
            tag.putString(TAG_MODE, pending.mode.lowerName());
            tag.putDouble(TAG_CABLE, pending.cable);
            tag.putLong(TAG_CHUNK, pending.chunk);
        }
    }

    public static void load(AbstractVehicle vehicle, CompoundTag tag) {
        if (!tag.hasUUID(TAG_TRACTOR)) {
            return;
        }
        TowMode mode = TowMode.CABLE.lowerName().equals(tag.getString(TAG_MODE))
                ? TowMode.CABLE : TowMode.HITCH;
        vehicle.setPendingTow(new Pending(tag.getUUID(TAG_TRACTOR), tag.getString(TAG_HITCH), mode,
                tag.getDouble(TAG_CABLE), tag.contains(TAG_CHUNK, Tag.TAG_LONG) ? tag.getLong(TAG_CHUNK) : UNKNOWN_CHUNK));
    }

    /** Retries finding a saved-pending tractor; halves of a train can load in either order. Gives up after {@link #RELINK_TIMEOUT} rather than hunt forever for one destroyed while unloaded. */
    public static void tickPending(AbstractVehicle vehicle) {
        Pending pending = vehicle.pendingTow();
        if (pending == null || !(vehicle.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Unloaded != missing: timer only accrues while the tractor's chunk holds loaded entities.
        if (pending.chunk == UNKNOWN_CHUNK || serverLevel.areEntitiesLoaded(pending.chunk)) {
            if (++pending.ticks > RELINK_TIMEOUT) {
                vehicle.setPendingTow(null);
                return;
            }
        }
        if (++pending.attempts % RELINK_INTERVAL != 0) {
            return;
        }
        Entity found = serverLevel.getEntity(pending.tractor);
        if (!(found instanceof AbstractVehicle tractor)) {
            return;
        }
        HitchInfo hitch = tractor.towing.hitch(pending.hitch);
        if (hitch == null) {
            vehicle.setPendingTow(null);
            return;
        }
        if (!couple(tractor, vehicle, hitch, pending.mode)) {
            vehicle.setPendingTow(null);
        }
    }

}
