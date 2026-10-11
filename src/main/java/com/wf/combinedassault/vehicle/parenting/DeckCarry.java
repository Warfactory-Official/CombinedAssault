package com.wf.combinedassault.vehicle.parenting;

import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;

/**
 * The ride a vehicle gets from the deck under it while it is still awake. Deck boxes hold/stop a
 * vehicle but never move it along, so a vehicle not asleep in a {@link VehicleHarness} would stand
 * still in world space as the deck slides out from under it (~15 blocks/s at a carrier's full speed).
 * Same carry riders get: offset in the carrier's frame kept each tick; next tick, the platform's
 * motion at that offset is applied to wherever the vehicle has since moved itself.
 */
public final class DeckCarry {

    /** Beyond this displacement in one tick, the carrier reads as jumped, not moved. */
    private static final double MAX_CARRY_PER_TICK = 16.0;

    /** Carry outlives its last support contact this long, so one contactless tick is not a dropped car. */
    private static final int GRACE_TICKS = 5;

    /** Units: blocks/tick. */
    private static final double MAX_HANDOFF_SPEED = 4.0;

    /**
     * A vehicle set down on a moving hull during this window rides it unsupported; falls in the
     * hull's frame otherwise.
     */
    private static final int SEEK_TICKS = 40;
    private static final double SEEK_REACH = 8.0;

    private final Vector3d localPos = new Vector3d();
    private final Vector3d scratch = new Vector3d();

    // Client only: the vehicle's pose in the carrier's frame.
    private final Vector3d localNow = new Vector3d();
    private final Quaternionf localRotationNow = new Quaternionf();
    private final Quaternionf scratchRotation = new Quaternionf();
    private final Quaternionf scratchInverse = new Quaternionf();
    private final Matrix3f scratchBasis = new Matrix3f();
    private final float[] scratchEuler = new float[3];

    private double worldX, worldY, worldZ;
    private double lastCarryX, lastCarryY, lastCarryZ;
    private float carrierYaw;
    private int carrierId = -1;
    private int grace;

    public void clear(AbstractVehicle vehicle) {
        if (carrierId >= 0) {
            vehicle.setDeckCarrierId(-1);
        }
        carrierId = -1;
        grace = 0;
        lastCarryX = 0;
        lastCarryY = 0;
        lastCarryZ = 0;
    }

    /**
     * Takes over the ride, and the velocity owed for it, from a harness that has just let go. A
     * waking harness hands over its carry velocity, right for a vehicle that's leaving, wrong for one
     * that's not (still on the deck, would set off at the ship's speed: pilot climbs in, plane walks
     * off the bow in a dozen ticks). Can't tell which at wake time, so not guessed: velocity passed as
     * a debt, paid by {@link #handoff} only once this carry itself gives up. Pose also passed across,
     * since a carry is a delta between ticks and a fresh one has nothing to diff against yet.
     */
    public void adopt(AbstractVehicle vehicle, AbstractVehicle carrier,
                      double carryX, double carryY, double carryZ) {
        lastCarryX = carryX;
        lastCarryY = carryY;
        lastCarryZ = carryZ;
        DeckSnapshot deck = carrier.deckSnapshot();
        if (carrier.isRemoved() || carrier.level() != vehicle.level() || deck.count() == 0) {
            // Nothing left to ride, so the debt falls due now.
            handoff(vehicle);
            clear(vehicle);
            return;
        }
        VehicleParenting.toLocal(deck, vehicle.getX(), vehicle.getY(), vehicle.getZ(), localPos);
        worldX = vehicle.getX();
        worldY = vehicle.getY();
        worldZ = vehicle.getZ();
        carrierYaw = carrier.getYRot();
        carrierId = carrier.getId();
        grace = GRACE_TICKS;
        vehicle.setDeckCarrierId(carrierId);
    }

    public void tick(AbstractVehicle vehicle) {
        if (VehicleHarness.isHarnessed(vehicle)) {
            // Harness carries it now and owes the ride on release; a handoff here is paid twice.
            clear(vehicle);
            return;
        }
        CarrierLink link = vehicle.carrierLink();
        AbstractVehicle carrier = link.carrier();
        boolean seeking = vehicle.tickCount < SEEK_TICKS && !vehicle.deckSeekBlocked();
        if (seeking && (carrier == null || !over(vehicle, carrier))) {
            AbstractVehicle below = deckBelow(vehicle);
            if (below != null) {
                carrier = below;
            }
        }
        if (carrier != null && carrierId == carrier.getId() && VehicleHarness.unloaded(carrier)) {
            // Hull's chunk unloaded under it: hold here as a park until it returns, not fall into the sea.
            vehicle.setPendingMount(VehicleHarness.parkAt(vehicle, carrier));
            clear(vehicle);
            return;
        }
        if (carrier == null || carrier.isRemoved()) {
            handoff(vehicle);
            clear(vehicle);
            return;
        }
        grace = link.supported() && link.carrier() == carrier || seeking && over(vehicle, carrier) ? GRACE_TICKS : grace - 1;
        if (grace <= 0) {
            // Vehicle leaving a moving deck keeps the deck's velocity; else a swell-thrown car lands
            // where the ship was, not where it is.
            handoff(vehicle);
            clear(vehicle);
            return;
        }
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.deckCount() == 0) {
            handoff(vehicle);
            clear(vehicle);
            return;
        }
        if (carrierId == carrier.getId()) {
            Vector3d target = DeckFrame.toWorld(deck.rotation(), deck.pivotX(), deck.pivotY(), deck.pivotZ(),
                    localPos.x, localPos.y, localPos.z, scratch);
            double dx = target.x - worldX;
            double dy = target.y - worldY;
            double dz = target.z - worldZ;
            if (dx * dx + dy * dy + dz * dz <= MAX_CARRY_PER_TICK * MAX_CARRY_PER_TICK) {
                if (dx != 0 || dy != 0 || dz != 0) {
                    vehicle.setPos(vehicle.getX() + dx, vehicle.getY() + dy, vehicle.getZ() + dz);
                }
                lastCarryX = dx;
                lastCarryY = dy;
                lastCarryZ = dz;
                float deltaYaw = Mth.wrapDegrees(carrier.getYRot() - carrierYaw);
                if (deltaYaw != 0) {
                    vehicle.setYRot(vehicle.getYRot() + deltaYaw);
                }
            }
        }
        VehicleParenting.toLocal(deck, vehicle.getX(), vehicle.getY(), vehicle.getZ(), localPos);
        worldX = vehicle.getX();
        worldY = vehicle.getY();
        worldZ = vehicle.getZ();
        carrierYaw = carrier.getYRot();
        carrierId = carrier.getId();
        DeckFrame.fromEulerYXZ(vehicle.getYRot(), vehicle.getXRot(), vehicle.getZRot(), scratchRotation);
        vehicle.setDeckLocal(localPos, DeckFrame.toLocalRotation(deck.inverse(), scratchRotation, scratchInverse));
        vehicle.setDeckCarrierId(carrierId);
    }

    /**
     * Places a vehicle riding a deck at its synced deck-frame pose against the carrier as rendered.
     * World placement against a carrier still interpolating = one tick of the ship's way off, every
     * tick; deck-frame pose derived from both reported poses stays correct whenever their updates
     * land in different client ticks.
     * @return whether the pose came from the carrier
     */
    public boolean clientFollow(AbstractVehicle vehicle) {
        int id = vehicle.deckCarrierId();
        if (id < 0 || !(vehicle.level().getEntity(id) instanceof AbstractVehicle carrier)) {
            return false;
        }
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.count() == 0) {
            return false;
        }
        // Server's own deck-frame pose: never a difference of two entities' separately timed updates.
        localNow.set(vehicle.deckLocal());
        localRotationNow.set(vehicle.deckLocalRotation());
        if (vehicle.clientLerpSteps() > 0) {
            vehicle.takeClientLerpStep();
        }
        // Place it against the frame as rendered.
        Vector3d target = DeckFrame.toWorld(deck.rotation(), deck.pivotX(), deck.pivotY(), deck.pivotZ(),
                localNow.x, localNow.y, localNow.z, scratch);
        DeckFrame.toWorldRotation(deck.rotation(), localRotationNow, scratchRotation);
        DeckFrame.toEulerYXZ(scratchRotation, scratchBasis, scratchEuler);
        vehicle.setPos(target.x, target.y, target.z);
        vehicle.setYRot(scratchEuler[0]);
        vehicle.setXRot(scratchEuler[1]);
        vehicle.setZRot(scratchEuler[2]);
        vehicle.updateOBBs();
        VehicleParenting.reapply(vehicle);
        return true;
    }

    @Nullable
    private AbstractVehicle deckBelow(AbstractVehicle vehicle) {
        List<AbstractVehicle> candidates = CarrierDecks.overlapping(vehicle,
                vehicle.getBoundingBox().expandTowards(0, -SEEK_REACH, 0));
        for (int i = 0, size = candidates.size(); i < size; i++) {
            if (over(vehicle, candidates.get(i))) {
                return candidates.get(i);
            }
        }
        return null;
    }

    /** A deck box under the vehicle's origin, at most {@link #SEEK_REACH} down. */
    private boolean over(AbstractVehicle vehicle, AbstractVehicle carrier) {
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.deckCount() == 0) {
            return false;
        }
        VehicleParenting.toLocal(deck, vehicle.getX(), vehicle.getY(), vehicle.getZ(), scratch);
        double top = DeckFrame.supportUnder(deck.deckBoxes(), deck.deckCount(), scratch.x, scratch.y, scratch.z, SEEK_REACH);
        return top <= scratch.y + 0.5;
    }

    private void handoff(AbstractVehicle vehicle) {
        double speed = Math.sqrt(lastCarryX * lastCarryX + lastCarryY * lastCarryY + lastCarryZ * lastCarryZ);
        if (speed <= 1.0E-4) {
            return;
        }
        double scale = speed > MAX_HANDOFF_SPEED ? MAX_HANDOFF_SPEED / speed : 1.0;
        vehicle.setDeltaMovement(vehicle.getDeltaMovement().add(
                lastCarryX * scale, lastCarryY * scale, lastCarryZ * scale));
    }

}
