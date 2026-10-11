package com.wf.combinedassault.vehicle.parenting;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.api.entity.ICustomVehicle;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Placing vehicles on the mount points other vehicles declare, and keeping them there across a save.
 *
 * <p>The holding itself is {@link VehicleHarness}'s locked mode; everything here is about deciding
 * which point a click meant, whether the thing in hand belongs there, and how the pair find each
 * other again after a reload.
 */
public final class VehicleMounts {

    private static final String TAG_CARRIER = "MountCarrier";
    private static final String TAG_POINT = "MountPoint";
    private static final String TAG_CHUNK = "MountCarrierChunk";
    private static final String TAG_OFFSET = "MountOffset";
    private static final String TAG_POSE = "MountPose";
    private static final String TAG_PARKED = "MountParked";

    /** How near a point the player has to be aiming, in blocks perpendicular to their look. */
    private static final double AIM_TOLERANCE = 2.0;

    /** How far along their look a point may be. Beyond this they are aiming past the vehicle. */
    private static final double AIM_REACH = 16.0;

    /** Ticks between attempts to find a carrier that has not loaded yet. */
    private static final int RELINK_INTERVAL = 10;

    /** Ticks a saved mount keeps looking while its carrier's chunk has entities loaded. */
    private static final int RELINK_TIMEOUT = 20 * 60;

    private VehicleMounts() {
    }

    /**
     * A mount or deck park waiting for its carrier: read back from a save, or left behind when the
     * carrier's chunk unloaded first. Point pose kept => a carrier with no pack (stock) restores too.
     * {@code parked}: {@code point} = harness pose in the deck frame (pivot-relative), id empty.
     */
    public static final class Pending {

        public final UUID carrier;
        public final MountPointInfo point;
        public final boolean parked;
        /** Carrier's chunk when last seen; timeout only runs while its entities are loaded. */
        final long chunk;
        public int ticks;
        int attempts;

        Pending(UUID carrier, MountPointInfo point, boolean parked, long chunk) {
            this.carrier = carrier;
            this.point = point;
            this.parked = parked;
            this.chunk = chunk;
        }

    }

    /** A pending mount back onto carrier, for a vehicle whose carrier is leaving the level. */
    public static Pending pendingFor(DeckCarrier carrier, MountPointInfo point) {
        return new Pending(carrier.getUUID(), point, false, chunkOf(carrier));
    }

    /** A pending park at a pose in the carrier's deck frame. */
    static Pending pendingPark(DeckCarrier carrier, Vector3d local, Quaternionf rotation) {
        MountPointInfo point = new MountPointInfo();
        point.offset = new Vec3(local.x, local.y, local.z);
        float[] euler = DeckFrame.toEulerYXZ(rotation, new Matrix3f(), new float[3]);
        point.yaw = euler[0];
        point.pitch = euler[1];
        point.roll = euler[2];
        return new Pending(carrier.getUUID(), point, true, chunkOf(carrier));
    }

    private static long chunkOf(DeckCarrier carrier) {
        return ChunkPos.asLong(BlockPos.containing(carrier.position()));
    }

    // ---------------------------------------------------------------- geometry

    /**
     * Where a point sits in the world right now. Offsets are stated from the vehicle origin and the
     * hull turns about its pivot, which is the same conversion a seat uses.
     */
    public static Vec3 worldPositionOf(AbstractVehicle carrier, MountPointInfo point) {
        Vector3f rotated = carrier.rotYXZ()
                .transform(point.offset.subtract(carrier.centerOffset).toVector3f());
        return carrier.position().add(carrier.centerOffset).add(rotated.x, rotated.y, rotated.z);
    }

    // ---------------------------------------------------------------- occupancy

    public static boolean isOccupied(AbstractVehicle carrier, String pointId) {
        for (AbstractVehicle mounted : carrier.harnessedVehicles()) {
            if (pointId.equals(VehicleHarness.mountIdOf(mounted))) {
                return true;
            }
        }
        return false;
    }

    /** Null when unoccupied. */
    @Nullable
    public static AbstractVehicle occupantOf(AbstractVehicle carrier, String pointId) {
        for (AbstractVehicle mounted : carrier.harnessedVehicles()) {
            if (pointId.equals(VehicleHarness.mountIdOf(mounted))) {
                return mounted;
            }
        }
        return null;
    }

    /**
     * Picks the point the player is aiming at that would take this vehicle.
     *
     * <p>Aim rather than proximity, so a bed with two brackets a block apart is still two brackets.
     */
    @Nullable
    public static MountPointInfo aimedPoint(AbstractVehicle carrier, Player player,
                                            String vehicleId, List<String> tags, float mass) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        MountPointInfo best = null;
        double bestOffAxis = Double.MAX_VALUE;
        for (MountPointInfo point : carrier.mountPoints) {
            if (!point.accepts(vehicleId, tags)) {
                continue;
            }
            if (point.maxMass > 0 && mass > point.maxMass) {
                continue;
            }
            if (isOccupied(carrier, point.id)) {
                continue;
            }
            Vec3 world = worldPositionOf(carrier, point);
            double along = world.subtract(eye).dot(look);
            if (along < 0 || along > AIM_REACH) {
                continue;
            }
            double offAxis = world.distanceTo(eye.add(look.scale(along)));
            if (offAxis < bestOffAxis && offAxis <= AIM_TOLERANCE) {
                bestOffAxis = offAxis;
                best = point;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- placing

    /**
     * Tries to bolt the vehicle in the player's hand onto a point of this carrier.
     * @return whether the click was consumed; false means this carrier has nothing to do with
     * mounting and the click should fall through to whatever it would have done (boarding, usually)
     */
    public static boolean place(AbstractVehicle carrier, Player player, ItemStack stack) {
        if (!AllConfigs.Cached.vehicleMounts || carrier.mountPoints.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ResourceLocation vehicleId = ResourceLocation.tryParse(tag.getString(ICustomVehicle.TAG_VEHICLE_ID));
        if (vehicleId == null) {
            return false;
        }
        Optional<BaseVehicleData> found = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId);
        if (found.isEmpty()) {
            return false;
        }
        BaseVehicleData<?> data = found.get();
        if (!data.getMountable().enabled) {
            player.displayClientMessage(Component.translatable("message.combinedassault.mount.not_mountable"), true);
            return true;
        }
        MountPointInfo point = aimedPoint(carrier, player, vehicleId.toString(),
                data.getMountable().tags, data.getPhysicsInfo().mass);
        if (point == null) {
            player.displayClientMessage(Component.translatable("message.combinedassault.mount.no_point"), true);
            return true;
        }
        Vec3 position = worldPositionOf(carrier, point);
        AbstractVehicle mounted = data.construct(carrier.level(), position, point.pitch,
                carrier.getYRot() + point.yaw);
        carrier.level().addFreshEntity(mounted);
        if (!VehicleHarness.mount(mounted, carrier, point)) {
            mounted.discard();
            return true;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return true;
    }

    public static boolean unmount(AbstractVehicle mounted) {
        if (!VehicleHarness.isMounted(mounted)) {
            return false;
        }
        VehicleHarness.detach(mounted, true);
        return true;
    }

    // ---------------------------------------------------------------- persistence

    /** Records the mount or deck park, if there is one, so it survives a reload. */
    public static void save(AbstractVehicle vehicle, CompoundTag compound) {
        // Still waiting for a carrier: keep the link rather than forgetting it because the world saved mid-search.
        Pending pending = vehicle.pendingMount() != null ? vehicle.pendingMount() : VehicleHarness.pendingOf(vehicle);
        if (pending == null) {
            return;
        }
        compound.putUUID(TAG_CARRIER, pending.carrier);
        compound.putBoolean(TAG_PARKED, pending.parked);
        compound.putLong(TAG_CHUNK, pending.chunk);
        compound.putString(TAG_POINT, pending.point.id);
        ListTag offset = new ListTag();
        offset.add(DoubleTag.valueOf(pending.point.offset.x));
        offset.add(DoubleTag.valueOf(pending.point.offset.y));
        offset.add(DoubleTag.valueOf(pending.point.offset.z));
        compound.put(TAG_OFFSET, offset);
        ListTag pose = new ListTag();
        pose.add(FloatTag.valueOf(pending.point.yaw));
        pose.add(FloatTag.valueOf(pending.point.pitch));
        pose.add(FloatTag.valueOf(pending.point.roll));
        compound.put(TAG_POSE, pose);
    }

    /** Reads a recorded mount back as a pending link; the carrier is resolved on a later tick. */
    public static void load(AbstractVehicle vehicle, CompoundTag compound) {
        if (!compound.hasUUID(TAG_CARRIER) || !compound.contains(TAG_POINT, Tag.TAG_STRING)) {
            return;
        }
        MountPointInfo point = new MountPointInfo();
        point.id = compound.getString(TAG_POINT);
        ListTag offset = compound.getList(TAG_OFFSET, Tag.TAG_DOUBLE);
        if (offset.size() == 3) {
            point.offset = new Vec3(offset.getDouble(0), offset.getDouble(1), offset.getDouble(2));
        }
        ListTag pose = compound.getList(TAG_POSE, Tag.TAG_FLOAT);
        if (pose.size() == 3) {
            point.yaw = pose.getFloat(0);
            point.pitch = pose.getFloat(1);
            point.roll = pose.getFloat(2);
        }
        vehicle.setPendingMount(new Pending(compound.getUUID(TAG_CARRIER), point, compound.getBoolean(TAG_PARKED),
                compound.contains(TAG_CHUNK, Tag.TAG_LONG) ? compound.getLong(TAG_CHUNK) : UNKNOWN_CHUNK));
    }

    /** No chunk recorded => timeout always runs. */
    private static final long UNKNOWN_CHUNK = Long.MIN_VALUE;

    /**
     * Re-establishes a saved mount once its carrier is in the level.
     *
     * <p>Both ends were saved at the same place, so nothing moves when this succeeds; it only puts the
     * parenting back. Server side.
     */
    public static void tickPending(AbstractVehicle vehicle) {
        Pending pending = vehicle.pendingMount();
        if (pending == null) {
            return;
        }
        if (VehicleHarness.isHarnessed(vehicle)) {
            vehicle.setPendingMount(null);
            return;
        }
        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // An unloaded carrier is not a missing one: wait as long as it stays unloaded.
        if (pending.chunk == UNKNOWN_CHUNK || serverLevel.areEntitiesLoaded(pending.chunk)) {
            if (++pending.ticks > RELINK_TIMEOUT) {
                // The carrier is not coming. Standing free is the honest outcome.
                vehicle.setPendingMount(null);
                return;
            }
        }
        if (++pending.attempts % RELINK_INTERVAL != 0) {
            return;
        }
        relink(vehicle, serverLevel, pending);
    }

    /** Released from a hold with its carrier: reattach now, not on the next {@link #RELINK_INTERVAL}. */
    public static void relinkNow(AbstractVehicle vehicle) {
        Pending pending = vehicle.pendingMount();
        if (pending != null && !VehicleHarness.isHarnessed(vehicle) && vehicle.level() instanceof ServerLevel serverLevel) {
            relink(vehicle, serverLevel, pending);
        }
    }

    private static void relink(AbstractVehicle vehicle, ServerLevel serverLevel, Pending pending) {
        Entity found = serverLevel.getEntity(pending.carrier);
        if (found == null) {
            return;
        }
        if (pending.parked) {
            // Deck = carrier's own scan; none before its first tick => keep holding.
            if (!(found instanceof AbstractVehicle hull) || hull.isRemoved() || hull.deckSnapshot().deckCount() == 0) {
                return;
            }
            VehicleHarness.repark(vehicle, hull, pending.point);
            vehicle.setPendingMount(null);
            return;
        }
        MountPointInfo point = pending.point;
        DeckCarrier carrier;
        if (found instanceof AbstractVehicle hull) {
            // The pack is authoritative over a saved pose; a point it no longer declares is gone.
            point = hull.mountPoints.stream().filter(declared -> declared.id.equals(pending.point.id))
                    .findFirst().orElse(null);
            carrier = hull;
        } else {
            carrier = DeckCarriers.of(found);
        }
        if (carrier != null && point != null) {
            VehicleHarness.mount(vehicle, carrier, point);
        }
        vehicle.setPendingMount(null);
    }

}
