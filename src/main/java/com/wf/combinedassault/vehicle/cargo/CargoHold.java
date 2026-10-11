package com.wf.combinedassault.vehicle.cargo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.vehicle.parenting.DeckFrame;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.parenting.VehicleMounts;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.pojo.CargoInfo;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Vehicles stowed in a carrier's hold: entity NBT inside the carrier while stowed (saved, unloaded, moved and
 * dimension-changed with it; no tick, no collision). {@code door} shut => stow; open + grounded => unload at the
 * stowed pose; open + airborne => chuted ones leave by the rear, one per {@link #DROP_INTERVAL}. Unchuted cargo never
 * leaves an airborne carrier.
 */
public final class CargoHold {

    public static final String TAG = "CargoHold";

    /** Blocks a stowed vehicle's pieces may overhang the bay. */
    private static final double FIT = 0.3;
    /** Blocks/tick: slower => grounded for unloading. */
    private static final double GROUND_SPEED = 0.3;
    private static final int DROP_INTERVAL = 20;
    /** Blocks between the bay's rear-bottom edge and a dropped vehicle's hull. */
    private static final double EXIT_GAP = 3.0;
    /** Blocks/tick rearward, carrier frame, on top of the carrier's velocity. */
    private static final double EXIT_PUSH = 0.3;

    /**
     * @param local  origin in the carrier's model frame
     * @param lo, hi hull bounds about its own origin, own frame
     * @param parent carrying vehicle's UUID for a mount stowed with it; null = top level
     */
    private record Stowed(CompoundTag entity, Vec3 local, Quaternionf rotation, Vec3 lo, Vec3 hi, float mass,
                          String model, boolean chute, @Nullable UUID uuid, @Nullable UUID parent) {
    }

    private final AbstractVehicle carrier;
    private final CargoInfo info;
    private final List<Stowed> stowed = new ArrayList<>();
    private Boolean doorWasOpen;
    private int dropCooldown;

    public CargoHold(AbstractVehicle carrier, CargoInfo info) {
        this.carrier = carrier;
        this.info = info;
    }

    // ---------------------------------------------------------------- frames

    private Vec3 toLocal(Vec3 world) {
        Vec3 pivot = carrier.centerOffset;
        Vector3f d = world.subtract(carrier.position()).subtract(pivot).toVector3f();
        carrier.rotYXZ().conjugate().transform(d);
        return new Vec3(d.x + pivot.x, d.y + pivot.y, d.z + pivot.z);
    }

    private Vec3 toWorld(Vec3 local) {
        Vec3 pivot = carrier.centerOffset;
        Vector3f d = local.subtract(pivot).toVector3f();
        carrier.rotYXZ().transform(d);
        return carrier.position().add(pivot).add(d.x, d.y, d.z);
    }

    private Vec3 bayMin() {
        return info.bayCenter.subtract(info.baySize.scale(0.5));
    }

    private Vec3 bayMax() {
        return info.bayCenter.add(info.baySize.scale(0.5));
    }

    private boolean inside(Vec3 local, double margin) {
        Vec3 lo = bayMin();
        Vec3 hi = bayMax();
        return local.x >= lo.x - margin && local.x <= hi.x + margin && local.y >= lo.y - margin && local.y <= hi.y + margin
                && local.z >= lo.z - margin && local.z <= hi.z + margin;
    }

    // ---------------------------------------------------------------- door

    @Nullable
    private SwitchableUnit<?> door;
    private boolean doorResolved;

    /** Cached: parts rebuild only with a new hold (same data apply). */
    @Nullable
    private SwitchableUnit<?> door() {
        if (!doorResolved) {
            doorResolved = true;
            for (PartUnit<?> part : carrier.getPartUnits()) {
                if (part.getData().getId().equals(info.door) && part instanceof SwitchableUnit<?> switchable) {
                    door = switchable;
                    break;
                }
            }
        }
        return door;
    }

    public boolean isDoor(PartUnit<?> part) {
        return part.getData().getId().equals(info.door);
    }

    /**
     * Whether {@code part} may close now; false => stays open, reason shown to {@code player}. Every vehicle in the bay
     * must stow, else closing would fly it as a live deck rider.
     */
    public boolean mayClose(PartUnit<?> part, @Nullable Player player) {
        if (!isDoor(part) || !(part instanceof SwitchableUnit<?> door) || !door.isOn()) {
            return true;
        }
        String refusal = null;
        float mass = mass();
        for (AbstractVehicle vehicle : inBay()) {
            if (!vehicle.getPassengers().isEmpty()) {
                refusal = "occupied";
            } else if (towLinked(vehicle)) {
                refusal = "towing";
            } else if (!fits(vehicle)) {
                refusal = "sticks_out";
            } else {
                mass += groupMass(vehicle);
                continue;
            }
            break;
        }
        if (refusal == null && info.maxMass > 0 && mass > info.maxMass) {
            refusal = "too_heavy";
        }
        if (refusal != null && player != null) {
            player.displayClientMessage(Component.translatable("message.combinedassault.cargo." + refusal), true);
        }
        return refusal == null;
    }

    /** Top-level vehicles whose origin is in the bay: not this carrier, not mounted on anything. */
    private List<AbstractVehicle> inBay() {
        Vec3 lo = toWorld(bayMin());
        Vec3 hi = toWorld(bayMax());
        double reach = info.baySize.length();
        List<AbstractVehicle> found = new ArrayList<>();
        for (AbstractVehicle vehicle : carrier.level().getEntitiesOfClass(AbstractVehicle.class,
                new net.minecraft.world.phys.AABB(lo, hi).inflate(reach * 0.5))) {
            if (vehicle == carrier || vehicle.isRemoved() || VehicleHarness.isMounted(vehicle)
                    || !inside(toLocal(vehicle.position()), 0)) {
                continue;
            }
            found.add(vehicle);
        }
        return found;
    }

    private boolean fits(AbstractVehicle vehicle) {
        List<VehicleCubeOBB> pieces = new ArrayList<>(vehicle.getVehicleCubeOBBs());
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            pieces.addAll(part.getPartCubeOBBs());
        }
        for (VehicleCubeOBB piece : pieces) {
            for (Vector3f corner : piece.obb().getVertices()) {
                if (!inside(toLocal(new Vec3(corner)), FIT)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean towLinked(AbstractVehicle vehicle) {
        return TowLinks.isTowed(vehicle) || TowLinks.tows(vehicle);
    }

    private static float groupMass(AbstractVehicle vehicle) {
        float mass = vehicle.physicsEngine.mass;
        for (AbstractVehicle child : vehicle.harnessedVehicles()) {
            mass += groupMass(child);
        }
        return mass;
    }

    // ---------------------------------------------------------------- tick

    public void tick() {
        SwitchableUnit<?> door = door();
        if (door == null) {
            return;
        }
        boolean open = door.isOn();
        if (doorWasOpen == null) {
            doorWasOpen = open;
        }
        if (!open && doorWasOpen) {
            stowAll();
        } else if (open && !stowed.isEmpty()) {
            if (grounded()) {
                unloadAll();
            } else if (--dropCooldown <= 0) {
                dropCooldown = DROP_INTERVAL;
                dropNext();
            }
        }
        doorWasOpen = open;
    }

    private boolean grounded() {
        Vec3 v = carrier.getDeltaMovement();
        return carrier.onGround() && v.x * v.x + v.z * v.z < GROUND_SPEED * GROUND_SPEED;
    }

    public float mass() {
        float mass = 0;
        for (Stowed s : stowed) {
            mass += s.mass;
        }
        return mass;
    }

    public boolean isEmpty() {
        return stowed.isEmpty();
    }

    // ---------------------------------------------------------------- stow

    private void stowAll() {
        int before = stowed.size();
        for (AbstractVehicle vehicle : inBay()) {
            if (vehicle.getPassengers().isEmpty() && !towLinked(vehicle) && fits(vehicle)) {
                stow(vehicle);
            }
        }
        if (stowed.size() != before) {
            changed();
        }
    }

    private void stow(AbstractVehicle vehicle) {
        // Parked on this deck: the saved park would pull it back aboard wherever it is released.
        VehicleHarness.detach(vehicle, false);
        vehicle.deckCarry().clear(vehicle);
        vehicle.setPendingMount(null);
        List<AbstractVehicle> group = new ArrayList<>();
        collect(vehicle, group);
        List<Stowed> entries = new ArrayList<>();
        for (AbstractVehicle member : group) {
            UUID parent = member != vehicle && VehicleHarness.carrierOf(member) instanceof AbstractVehicle up ? up.getUUID() : null;
            entries.add(snapshot(member, parent));
        }
        for (int i = group.size() - 1; i >= 0; i--) {
            group.get(i).discard();
        }
        stowed.addAll(entries);
    }

    private static void collect(AbstractVehicle vehicle, List<AbstractVehicle> out) {
        out.add(vehicle);
        for (AbstractVehicle child : new ArrayList<>(vehicle.harnessedVehicles())) {
            collect(child, out);
        }
    }

    private Stowed snapshot(AbstractVehicle vehicle, @Nullable UUID parent) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", EntityType.getKey(vehicle.getType()).toString());
        vehicle.saveWithoutId(tag);
        tag.remove("Passengers");
        Vec3 local = toLocal(vehicle.position());
        Quaternionf rotation = DeckFrame.toLocalRotation(carrier.rotYXZ().conjugate(),
                DeckFrame.fromEulerYXZ(vehicle.getYRot(), vehicle.getXRot(), vehicle.getZRot(), new Quaternionf()),
                new Quaternionf());
        Quaternionf inverse = vehicle.rotYXZ().conjugate();
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        List<VehicleCubeOBB> pieces = new ArrayList<>(vehicle.getVehicleCubeOBBs());
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            pieces.addAll(part.getPartCubeOBBs());
        }
        for (VehicleCubeOBB piece : pieces) {
            for (Vector3f corner : piece.obb().getVertices()) {
                Vector3f own = inverse.transform(corner.sub((float) vehicle.getX(), (float) vehicle.getY(), (float) vehicle.getZ()));
                lo[0] = Math.min(lo[0], own.x);
                lo[1] = Math.min(lo[1], own.y);
                lo[2] = Math.min(lo[2], own.z);
                hi[0] = Math.max(hi[0], own.x);
                hi[1] = Math.max(hi[1], own.y);
                hi[2] = Math.max(hi[2], own.z);
            }
        }
        String model = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId())
                .map(data -> data.getModel().source()).orElse("");
        return new Stowed(tag, local, rotation, new Vec3(lo[0], lo[1], lo[2]), new Vec3(hi[0], hi[1], hi[2]),
                vehicle.physicsEngine.mass, model, vehicle.parachute().fitted(), vehicle.getUUID(), parent);
    }

    // ---------------------------------------------------------------- release

    private void unloadAll() {
        boolean any = false;
        for (Stowed s : new ArrayList<>(stowed)) {
            if (s.parent == null) {
                any |= releaseGroup(s, toWorld(s.local), worldRotation(s.rotation), carrier.getDeltaMovement(), false);
            }
        }
        if (any) {
            changed();
        }
    }

    /** Carrier destroyed or killed: everything out where it sits, at the carrier's velocity. */
    public void releaseAll() {
        unloadAll();
    }

    private void dropNext() {
        Stowed next = null;
        for (Stowed s : stowed) {
            if (s.parent == null && s.chute && (next == null || s.local.z < next.local.z)) {
                next = s;
            }
        }
        if (next == null) {
            return;
        }
        Vec3 lo = bayMin();
        Vec3 exit = new Vec3(next.local.x, lo.y - EXIT_GAP - next.hi.y, lo.z - EXIT_GAP - next.hi.z);
        Vector3f push = carrier.rotYXZ().transform(new Vector3f(0, 0, (float) -EXIT_PUSH));
        if (releaseGroup(next, toWorld(exit), worldRotation(next.rotation),
                carrier.getDeltaMovement().add(push.x, push.y, push.z), true)) {
            changed();
        }
    }

    private Quaternionf worldRotation(Quaternionf local) {
        return DeckFrame.toWorldRotation(carrier.rotYXZ(), local, new Quaternionf());
    }

    /** Spawns {@code top} and its mounts; a member that fails to spawn stays stowed. */
    private boolean releaseGroup(Stowed top, Vec3 at, Quaternionf rotation, Vec3 velocity, boolean airdrop) {
        AbstractVehicle spawned = spawn(top, at, rotation, velocity, airdrop);
        if (spawned == null) {
            return false;
        }
        stowed.remove(top);
        releaseChildren(top, spawned);
        return true;
    }

    private void releaseChildren(Stowed parent, AbstractVehicle spawnedParent) {
        for (Stowed child : new ArrayList<>(stowed)) {
            if (parent.uuid != null && parent.uuid.equals(child.parent)) {
                AbstractVehicle spawned = spawn(child, spawnedParent.position(), new Quaternionf(), spawnedParent.getDeltaMovement(), false);
                if (spawned != null) {
                    stowed.remove(child);
                    VehicleMounts.relinkNow(spawned);
                    releaseChildren(child, spawned);
                }
            }
        }
    }

    @Nullable
    private AbstractVehicle spawn(Stowed s, Vec3 at, Quaternionf rotation, Vec3 velocity, boolean airdrop) {
        Optional<Entity> made = EntityType.create(s.entity.copy(), carrier.level());
        if (made.isEmpty() || !(made.get() instanceof AbstractVehicle vehicle)) {
            CombinedAssault.LOGGER.error("{}: stowed {} cannot be rebuilt; kept in the hold", carrier.getUUID(), s.entity.getString("id"));
            return null;
        }
        float[] euler = DeckFrame.toEulerYXZ(rotation, new Matrix3f(), new float[3]);
        vehicle.moveTo(at.x, at.y, at.z, euler[0], euler[1]);
        vehicle.setZRot(euler[2]);
        vehicle.setDeltaMovement(velocity);
        vehicle.fallDistance = 0;
        vehicle.setDeckSeekBlocked(airdrop);
        if (!carrier.level().addFreshEntity(vehicle)) {
            CombinedAssault.LOGGER.error("{}: stowed {} refused by the level; kept in the hold", carrier.getUUID(), vehicle.getUUID());
            return null;
        }
        return vehicle;
    }

    // ---------------------------------------------------------------- sync + persistence

    private void changed() {
        carrier.refreshMass();
        ListTag view = new ListTag();
        for (Stowed s : stowed) {
            if (s.model.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putString("m", s.model);
            entry.put("p", floats((float) s.local.x, (float) s.local.y, (float) s.local.z));
            entry.put("q", floats(s.rotation.x, s.rotation.y, s.rotation.z, s.rotation.w));
            view.add(entry);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("v", view);
        carrier.setCargoView(tag);
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Stowed s : stowed) {
            CompoundTag entry = new CompoundTag();
            entry.put("e", s.entity);
            entry.put("p", floats((float) s.local.x, (float) s.local.y, (float) s.local.z));
            entry.put("q", floats(s.rotation.x, s.rotation.y, s.rotation.z, s.rotation.w));
            entry.put("lo", floats((float) s.lo.x, (float) s.lo.y, (float) s.lo.z));
            entry.put("hi", floats((float) s.hi.x, (float) s.hi.y, (float) s.hi.z));
            entry.putFloat("mass", s.mass);
            entry.putString("model", s.model);
            entry.putBoolean("chute", s.chute);
            if (s.uuid != null) {
                entry.putUUID("uuid", s.uuid);
            }
            if (s.parent != null) {
                entry.putUUID("parent", s.parent);
            }
            list.add(entry);
        }
        return list;
    }

    public void load(ListTag list) {
        stowed.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ListTag q = entry.getList("q", Tag.TAG_FLOAT);
            stowed.add(new Stowed(entry.getCompound("e"), vec(entry.getList("p", Tag.TAG_FLOAT)),
                    new Quaternionf(q.getFloat(0), q.getFloat(1), q.getFloat(2), q.getFloat(3)),
                    vec(entry.getList("lo", Tag.TAG_FLOAT)), vec(entry.getList("hi", Tag.TAG_FLOAT)),
                    entry.getFloat("mass"), entry.getString("model"), entry.getBoolean("chute"),
                    entry.hasUUID("uuid") ? entry.getUUID("uuid") : null,
                    entry.hasUUID("parent") ? entry.getUUID("parent") : null));
        }
        changed();
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    private static Vec3 vec(ListTag list) {
        return new Vec3(list.getFloat(0), list.getFloat(1), list.getFloat(2));
    }

}
