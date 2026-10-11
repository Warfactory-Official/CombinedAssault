package com.wf.combinedassault.vehicle.parenting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;

import java.util.List;

/**
 * A vehicle parked on a carrier, physics off, pose derived from the carrier's instead.
 *
 * <p>Two things arrive here by different routes and leave by different ones. A vehicle that drove
 * onto a deck and stopped is <em>parked</em>: captured from wherever it came to rest, it wakes the
 * moment that stops being true: engine starts, someone takes the controls, the deck moves out from
 * under it, or it gets shot. A vehicle placed on a declared {@link MountPointInfo} is
 * <em>mounted</em>: pose came from the pack rather than physics, and none of those things release
 * it, since a mortar bolted to a pickup bed must stay crewed, fired and shot at without falling off.
 * Only an explicit unmount, or the carrier going away, ends a mount.
 */
public final class VehicleHarness {

    // How long a vehicle must be still on a deck before sleeping.
    private static final int DWELL_TICKS = 20;

    // Speed below which a vehicle counts as parked, in blocks and radians per tick.
    private static final double LINEAR_SLEEP = 0.02;
    private static final double ANGULAR_SLEEP = 0.002;

    // How far the deck under a sleeping vehicle may move before it is woken.
    private static final double SUPPORT_TOLERANCE = 0.1;

    // Ceiling on the velocity a waking vehicle inherits, matching the rider handoff.
    private static final double MAX_HANDOFF_SPEED = 4.0;

    // Displacement in one tick beyond which the carrier is read as having jumped rather than moved.
    private static final double MAX_CARRY_PER_TICK = 16.0;

    private final DeckCarrier carrier;

    /**
     * Set for a mount rather than a park. A locked harness ignores every wake condition; see the
     * class note for why each of them is wrong for an emplacement.
     */
    private boolean locked;

    /** The declared point this vehicle occupies, or null when it merely parked itself here. */
    @Nullable
    private MountPointInfo mountPoint;

    // The vehicle's own origin and orientation, in the carrier's frame. The authoritative pose.
    private final Vector3d localPos = new Vector3d();
    private final Quaternionf localRotation = new Quaternionf();
    private final Vector3d scratch = new Vector3d();
    private final Quaternionf scratchRotation = new Quaternionf();
    private final Quaternionf scratchInverse = new Quaternionf();
    private final Matrix3f scratchBasis = new Matrix3f();
    private final float[] scratchEuler = new float[3];

    // Height of the deck surface this vehicle settled on, in the carrier's frame.
    private double localSupportY;
    // World displacement the last apply made, handed over as velocity when the harness lets go.
    private double lastCarryX, lastCarryY, lastCarryZ;
    // Set on the client when the pose the server reported has changed and local must be re-derived.
    private boolean localDirty = true;
    private VehicleHarness(DeckCarrier carrier) {
        this.carrier = carrier;
    }

    // ---------------------------------------------------------------- state
    /** Null when not asleep on anything. */
    @Nullable
    public static DeckCarrier carrierOf(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        return harness == null ? null : harness.carrier;
    }

    public static boolean isHarnessed(AbstractVehicle vehicle) {
        return vehicle.harness() != null;
    }

    /** Whether this vehicle is bolted to a declared point rather than merely parked. */
    public static boolean isMounted(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        return harness != null && harness.locked;
    }

    /** The point a mounted vehicle occupies, or null when it is parked or free. */
    @Nullable
    public static String mountIdOf(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        return harness == null || harness.mountPoint == null ? null : harness.mountPoint.id;
    }

    @Nullable
    public static MountPointInfo mountPointOf(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        return harness == null ? null : harness.mountPoint;
    }

    /**
     * Bolts a vehicle to a declared point on a carrier.
     *
     * <p>Unlike {@link #attach}, nothing about the world has to agree: there is no deck under the
     * point and no support test, because the pack said the bracket is there.
     *
     * @return whether the mount took
     */
    public static boolean mount(AbstractVehicle vehicle, DeckCarrier carrier, MountPointInfo point) {
        if (carrier == vehicle || carrier.isRemoved() || vehicle.isRemoved()
                || carrier.level() != vehicle.level() || isHarnessed(vehicle)) {
            return false;
        }
        // A carries B carries A would apply each from the other's pose for ever.
        if (carries(carrier, vehicle)) {
            return false;
        }
        for (AbstractVehicle mounted : carrier.harnessedVehicles()) {
            if (point.id.equals(mountIdOf(mounted))) {
                return false;
            }
        }
        VehicleHarness harness = new VehicleHarness(carrier);
        harness.locked = true;
        harness.mountPoint = point;
        // Pack offsets are stated from the vehicle origin; the deck frame is centred on the pivot.
        Vec3 pivot = carrier.carrierCenterOffset();
        harness.localPos.set(point.offset.x - pivot.x,
                point.offset.y - pivot.y,
                point.offset.z - pivot.z);
        DeckFrame.fromEulerYXZ(point.yaw, point.pitch, point.roll, harness.localRotation);
        harness.localSupportY = Double.NaN;
        harness.localDirty = false;
        vehicle.setHarness(harness);
        vehicle.setHarnessDwell(0);
        vehicle.setDeltaMovement(Vec3.ZERO);
        vehicle.carrierLink().clear();
        carrier.harnessedVehicles().add(vehicle);
        vehicle.setHarnessCarrierId(carrier.getId());
        harness.apply(vehicle, carrier.deckSnapshot());
        return true;
    }

    /** Whether candidate is anywhere up the chain of carriers above root, root included. */
    private static boolean carries(DeckCarrier root, DeckCarrier candidate) {
        for (DeckCarrier above = root; above != null; ) {
            if (above == candidate) {
                return true;
            }
            // Only a vehicle can itself be carried, so anything else ends the chain.
            above = above instanceof AbstractVehicle carried ? carrierOf(carried) : null;
        }
        return false;
    }

    /**
     * Puts a harnessed vehicle where its carrier's pose says it is, from the vehicle's own tick. The
     * client reaches the same thing through {@link #clientFollow}. A pose written anywhere else is a
     * frozen render for that tick; see {@link #applyChildren}.
     */
    public static void follow(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        if (harness != null && !harness.carrier.isRemoved()) {
            harness.apply(vehicle, harness.carrier.deckSnapshot());
        }
    }

    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        VehicleHarness harness = vehicle.harness();
        if (harness != null) {
            if (harness.locked && unloaded(harness.carrier)) {
                vehicle.setPendingMount(VehicleMounts.pendingFor(harness.carrier, harness.mountPoint));
                detach(vehicle, false);
            } else if (harness.shouldWake(vehicle)) {
                releaseToDeck(vehicle, harness);
            }
            return;
        }
        if (!AllConfigs.Cached.deckHarness) {
            return;
        }
        if (!settled(vehicle)) {
            vehicle.setHarnessDwell(0);
            return;
        }
        if (vehicle.harnessDwell() + 1 < DWELL_TICKS) {
            vehicle.setHarnessDwell(vehicle.harnessDwell() + 1);
            return;
        }
        if (!attach(vehicle, vehicle.carrierLink().carrier())) {
            vehicle.setHarnessDwell(0);
        }
    }

    // ---------------------------------------------------------------- tick

    private static boolean settled(AbstractVehicle vehicle) {
        CarrierLink link = vehicle.carrierLink();
        AbstractVehicle carrier = link.carrier();
        if (carrier == null || !link.supported() || carrier.isRemoved()) {
            return false;
        }
        if (vehicle.isRemoved() || vehicle.noPhysics || !vehicle.collision) {
            return false;
        }
        if (vehicle.isEngineOn() || vehicle.getPower() > 0
                || vehicle.getControllingPassenger() != null) {
            return false;
        }
        Vec3 velocity = vehicle.getDeltaMovement();
        return velocity.lengthSqr() <= LINEAR_SLEEP * LINEAR_SLEEP
                && vehicle.physicsEngine.angularVelocity.length() <= ANGULAR_SLEEP;
    }


    /** Poses every child from the carrier's current pose, whatever has ticked. */
    public static void applyChildren(DeckCarrier carrier) {
        applyChildren(carrier, false);
    }

    /**
     * @param deferToOwnTick leave a child that is ticking but has not ticked yet this tick to pose
     *                       itself, which it does through {@link #follow}. A pose written before a
     *                       vehicle's own {@code setOldPosAndRot} is copied straight into its old pose,
     *                       and the renderer is left with nothing to interpolate across: the child
     *                       stands still for the frame and jumps at the tick.
     */
    public static void applyChildren(DeckCarrier carrier, boolean deferToOwnTick) {
        List<AbstractVehicle> children = carrier.harnessedVehicles();
        if (children.isEmpty()) {
            return;
        }
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.count() == 0) {
            return;
        }
        long gameTime = TickClock.now(carrier.level());
        for (int i = children.size() - 1; i >= 0; i--) {
            AbstractVehicle child = children.get(i);
            VehicleHarness harness = child.harness();
            if (harness == null || harness.carrier != carrier || child.isRemoved()
                    || child.level() != carrier.level()) {
                children.remove(i);
                if (harness != null && harness.carrier == carrier) {
                    detach(child, false);
                }
                continue;
            }
            // A child that is not ticking at all is still posed here, or its carrier sails out from
            // under it.
            boolean ticking = child.lastTickTime() >= gameTime - 1;
            if (!deferToOwnTick || !ticking || child.lastTickTime() == gameTime) {
                harness.apply(child, deck);
            }
        }
    }

    // "Attach" = falling asleep on a carrier.
    private static boolean attach(AbstractVehicle vehicle, @Nullable AbstractVehicle carrier) {
        if (carrier == null || carrier == vehicle || carrier.isRemoved()) {
            return false;
        }
        if (isHarnessed(carrier)) {
            return false;
        }
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.deckCount() == 0) {
            return false;
        }
        VehicleHarness harness = new VehicleHarness(carrier);
        harness.capture(vehicle, deck);
        if (Double.isNaN(harness.localSupportY) || !harness.supportedAt(deck)) {
            return false;
        }
        vehicle.setHarness(harness);
        vehicle.setHarnessDwell(0);
        vehicle.setDeltaMovement(Vec3.ZERO);
        vehicle.setOnGround(true);
        vehicle.carrierLink().clear();
        carrier.harnessedVehicles().add(vehicle);
        vehicle.setHarnessCarrierId(carrier.getId());
        return true;
    }

    // handoff: hands over the platform velocity it was being carried at.
    public static void detach(AbstractVehicle vehicle, boolean handoff) {
        VehicleHarness harness = vehicle.harness();
        if (harness == null) {
            return;
        }
        harness.carrier.harnessedVehicles().remove(vehicle);
        vehicle.setHarness(null);
        vehicle.setHarnessDwell(0);
        // Synced data is server only. The client reaches this through level removal,
        // where the value is meaningless.
        if (!vehicle.level().isClientSide()) {
            vehicle.setHarnessCarrierId(-1);
        }
        vehicle.carrierLink().clear();
        if (!handoff) {
            return;
        }
        double speed = Math.sqrt(harness.lastCarryX * harness.lastCarryX
                + harness.lastCarryY * harness.lastCarryY
                + harness.lastCarryZ * harness.lastCarryZ);
        if (speed > 1.0E-4) {
            double scale = speed > MAX_HANDOFF_SPEED ? MAX_HANDOFF_SPEED / speed : 1.0;
            vehicle.setDeltaMovement(vehicle.getDeltaMovement().add(
                    harness.lastCarryX * scale, harness.lastCarryY * scale,
                    harness.lastCarryZ * scale));
        }
    }

    static boolean unloaded(DeckCarrier carrier) {
        Entity.RemovalReason reason = carrier.getRemovalReason();
        return reason == Entity.RemovalReason.UNLOADED_TO_CHUNK || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER;
    }

    /** Carrier's chunk is unloading: mounted and parked vehicles hold still and wait for it to return. */
    public static void unloadAll(DeckCarrier carrier) {
        List<AbstractVehicle> children = carrier.harnessedVehicles();
        for (int i = children.size() - 1; i >= 0; i--) {
            AbstractVehicle child = children.get(i);
            VehicleMounts.Pending pending = pendingOf(child);
            if (pending != null) {
                child.setPendingMount(pending);
            }
            detach(child, false);
        }
        children.clear();
    }

    /**
     * The link to write for a vehicle carried by another: its mount, its park, or its awake ride on a
     * deck (restored as a park; wakes again on its own if it should). Null when free.
     */
    @Nullable
    static VehicleMounts.Pending pendingOf(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        if (harness != null) {
            if (harness.locked) {
                return harness.mountPoint == null ? null : VehicleMounts.pendingFor(harness.carrier, harness.mountPoint);
            }
            return harness.localDirty || !(harness.carrier instanceof AbstractVehicle)
                    ? null : VehicleMounts.pendingPark(harness.carrier, harness.localPos, harness.localRotation);
        }
        AbstractVehicle hull = vehicle.carrierLink().carrier();
        if (vehicle.deckCarrierId() < 0 || hull == null || hull.getId() != vehicle.deckCarrierId()) {
            return null;
        }
        return parkAt(vehicle, hull);
    }

    /** Park link for where the vehicle stands on hull's deck right now. */
    static VehicleMounts.Pending parkAt(AbstractVehicle vehicle, AbstractVehicle hull) {
        DeckSnapshot deck = hull.deckSnapshot();
        Vector3d local = DeckFrame.toLocal(deck.inverse(), deck.pivotX(), deck.pivotY(), deck.pivotZ(),
                vehicle.getX(), vehicle.getY(), vehicle.getZ(), new Vector3d());
        Quaternionf rotation = DeckFrame.toLocalRotation(deck.inverse(),
                DeckFrame.fromEulerYXZ(vehicle.getYRot(), vehicle.getXRot(), vehicle.getZRot(), new Quaternionf()),
                new Quaternionf());
        return VehicleMounts.pendingPark(hull, local, rotation);
    }

    /** Restores a saved park: same pose in the deck frame, asleep. */
    static void repark(AbstractVehicle vehicle, AbstractVehicle hull, MountPointInfo pose) {
        if (hull == vehicle || isHarnessed(vehicle) || carries(hull, vehicle)) {
            return;
        }
        DeckSnapshot deck = hull.deckSnapshot();
        VehicleHarness harness = new VehicleHarness(hull);
        harness.localPos.set(pose.offset.x, pose.offset.y, pose.offset.z);
        DeckFrame.fromEulerYXZ(pose.yaw, pose.pitch, pose.roll, harness.localRotation);
        harness.localSupportY = DeckFrame.supportUnder(deck.deckBoxes(), deck.deckCount(),
                harness.localPos.x, harness.localPos.y, harness.localPos.z, hullReach(vehicle));
        harness.localDirty = false;
        vehicle.setHarness(harness);
        vehicle.setHarnessDwell(0);
        vehicle.setDeltaMovement(Vec3.ZERO);
        vehicle.setOnGround(true);
        vehicle.carrierLink().clear();
        hull.harnessedVehicles().add(vehicle);
        vehicle.setHarnessCarrierId(hull.getId());
        harness.apply(vehicle, deck);
    }

    public static void releaseAll(DeckCarrier carrier, boolean handoff) {
        List<AbstractVehicle> children = carrier.harnessedVehicles();
        if (children.isEmpty()) {
            return;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            detach(children.get(i), handoff);
        }
        children.clear();
    }

    /** Wakes a parked vehicle. A mount is not woken by circumstance; only {@link #detach} ends one. */
    public static void wake(AbstractVehicle vehicle) {
        VehicleHarness harness = vehicle.harness();
        if (harness != null && !harness.locked) {
            releaseToDeck(vehicle, harness);
        }
    }

    /**
     * Wakes a vehicle, handing the ride, and the velocity owed for it, to the deck under it.
     *
     * <p>A harness hands a waking vehicle the velocity it was being carried at, which is right for
     * one that is leaving and wrong for one that is not: the deck is still under it, the carry
     * supplies the ship's motion again, and the vehicle sets off along the deck at the ship's own
     * speed. Which of the two this is cannot be told here, and the proxies for it are worse than
     * useless because they are nearly always right. {@link DeckCarry} finds out for certain, one
     * tick later, so it takes the velocity across as a debt and pays it where it falls due.
     */
    private static void releaseToDeck(AbstractVehicle vehicle, VehicleHarness harness) {
        DeckCarrier carrier = harness.carrier;
        double carryX = harness.lastCarryX;
        double carryY = harness.lastCarryY;
        double carryZ = harness.lastCarryZ;
        // Only a vehicle carrier has a deck for the debt to fall due on. Off anything else there is
        // nothing underneath, so the velocity is owed to the vehicle immediately.
        if (carrier instanceof AbstractVehicle hull) {
            detach(vehicle, false);
            vehicle.deckCarry().adopt(vehicle, hull, carryX, carryY, carryZ);
        } else {
            detach(vehicle, true);
        }
    }

    // ---------------------------------------------------------------- attach and detach

    // How far from a vehicle's own origin its deck can be, in blocks.
    private static double hullReach(AbstractVehicle vehicle) {
        return vehicle.getMainCubeOBB() == null
                ? 4.0 : vehicle.getMainCubeOBB().obb().extents().y * 2.0 + 1.0;
    }

    public static boolean clientFollow(AbstractVehicle vehicle) {
        int carrierId = vehicle.harnessCarrierId();
        VehicleHarness harness = vehicle.harness();
        if (carrierId < 0) {
            if (harness != null) {
                harness.carrier.harnessedVehicles().remove(vehicle);
                vehicle.setHarness(null);
            }
            return false;
        }
        DeckCarrier carrier = DeckCarriers.byId(vehicle.level(), carrierId);
        if (carrier == null || carrier.deckSnapshot().count() == 0) {
            return false;
        }
        if (harness == null || harness.carrier != carrier) {
            if (harness != null) {
                harness.carrier.harnessedVehicles().remove(vehicle);
            }
            harness = new VehicleHarness(carrier);
            vehicle.setHarness(harness);
            carrier.harnessedVehicles().add(vehicle);
        }
        harness.localPos.set(vehicle.deckLocal());
        harness.localRotation.set(vehicle.deckLocalRotation());
        harness.localDirty = false;
        harness.apply(vehicle, carrier.deckSnapshot());
        return true;
    }

    public DeckCarrier carrier() {
        return carrier;
    }

    private boolean shouldWake(AbstractVehicle vehicle) {
        if (locked) {
            // Only losing the carrier ends a mount. deckHarness is a performance switch for parked
            // vehicles, and switching it off must not drop everyone's emplacements on the floor.
            return vehicle.isRemoved() || carrier.isRemoved() || carrier.level() != vehicle.level();
        }
        if (!AllConfigs.Cached.deckHarness || vehicle.isRemoved() || vehicle.noPhysics
                || !vehicle.collision) {
            return true;
        }
        // A parked harness is only ever taken against a vehicle's deck; anything else is a mount.
        if (!(carrier instanceof AbstractVehicle hull)) {
            return true;
        }
        if (hull.isRemoved() || hull.level() != vehicle.level() || !hull.collision) {
            return true;
        }
        if (vehicle.isEngineOn() || vehicle.getPower() > 0
                || vehicle.getControllingPassenger() != null) {
            return true;
        }
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.deckCount() == 0) {
            return true;
        }
        return !supportedAt(deck);
    }

    private boolean supportedAt(DeckSnapshot deck) {
        return DeckFrame.supportsAt(deck.deckBoxes(), deck.deckCount(),
                localPos.x, localPos.z, localSupportY, SUPPORT_TOLERANCE);
    }

    private void apply(AbstractVehicle child, DeckSnapshot deck) {
        if (localDirty) {
            return;
        }
        if (!child.level().isClientSide()) {
            child.setDeckLocal(localPos, localRotation);
        }
        Vector3d target = DeckFrame.toWorld(deck.rotation(),
                deck.pivotX(), deck.pivotY(), deck.pivotZ(),
                localPos.x, localPos.y, localPos.z, scratch);
        double dx = target.x - child.getX();
        double dy = target.y - child.getY();
        double dz = target.z - child.getZ();
        boolean jumped = dx * dx + dy * dy + dz * dz > MAX_CARRY_PER_TICK * MAX_CARRY_PER_TICK;
        if (jumped && !locked) {
            capture(child, deck);
            setLastCarry(0, 0, 0);
            return;
        }

        DeckFrame.toWorldRotation(deck.rotation(), localRotation, scratchRotation);
        DeckFrame.toEulerYXZ(scratchRotation, scratchBasis, scratchEuler);
        boolean moved = dx != 0 || dy != 0 || dz != 0;
        boolean turned = scratchEuler[0] != child.getYRot() || scratchEuler[1] != child.getXRot()
                || scratchEuler[2] != child.getZRot();
        // A mount's pose is the pack's: snapped across a jump, never re-captured.
        if (jumped) {
            setLastCarry(0, 0, 0);
        } else {
            setLastCarry(dx, dy, dz);
        }
        if (!moved && !turned) {
            return;
        }
        if (moved) {
            child.setPos(target.x, target.y, target.z);
        }
        if (turned) {
            child.setYRot(scratchEuler[0]);
            child.setXRot(scratchEuler[1]);
            child.setZRot(scratchEuler[2]);
        }
        child.updateOBBs();
        VehicleParenting.reapply(child);
        // Grandchildren pose from child's fresh snapshot, not from the one child's own tick left.
        applyChildren(child, true);
    }

    private void capture(AbstractVehicle vehicle, DeckSnapshot deck) {
        DeckFrame.toLocal(deck.inverse(), deck.pivotX(), deck.pivotY(), deck.pivotZ(),
                vehicle.getX(), vehicle.getY(), vehicle.getZ(), localPos);
        DeckFrame.fromEulerYXZ(vehicle.getYRot(), vehicle.getXRot(), vehicle.getZRot(),
                scratchRotation);
        DeckFrame.toLocalRotation(deck.inverse(), scratchRotation, localRotation);
        localSupportY = DeckFrame.supportUnder(deck.deckBoxes(), deck.deckCount(),
                localPos.x, localPos.y, localPos.z, hullReach(vehicle));
        localDirty = false;
    }

    // ---------------------------------------------------------------- client stuff

    private void setLastCarry(double x, double y, double z) {
        lastCarryX = x;
        lastCarryY = y;
        lastCarryZ = z;
    }

}
