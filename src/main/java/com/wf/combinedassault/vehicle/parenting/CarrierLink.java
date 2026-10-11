package com.wf.combinedassault.vehicle.parenting;

import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.collision.BoxBuffer;
import com.wf.combinedassault.vehicle.collision.ContactSynthesis;
import com.wf.combinedassault.vehicle.collision.SweptHull;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.List;

/**
 * One vehicle's frozen view of the carrier beneath it, for the tick it was taken.
 * Deck geometry is queried in the carrier's frame to avoid expensive rotations of large boxes.
 */
public final class CarrierLink {

    /** Slack margin around the gather bound to compensate for frame coordinate rounding. */
    private static final double SELECT_MARGIN = 1.0;

    @Nullable
    private AbstractVehicle carrier;

    /** The carrier's published pose, copied rather than referenced. */
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf inverse = new Quaternionf();
    private double pivotX, pivotY, pivotZ;

    /** Carrier's frame. */
    private final BoxBuffer boxes = new BoxBuffer();

    /** The hull in the carrier's frame. Centre rewritten per cast; rotation fixed for the tick. */
    private final OBB localHull = new OBB(new Vector3f(), new Vector3f(), new Quaternionf());
    private final Quaternionf localRotation = new Quaternionf();
    private final OBB.SatFrame localFrame = new OBB.SatFrame();
    private Vector3f[] localAxes = new Vector3f[3];

    /** Trial pose for overlaps, testing a rotation not yet adopted. */
    private final OBB turnHull = new OBB(new Vector3f(), new Vector3f(), new Quaternionf());

    private final Vector3d scratchLocal = new Vector3d();
    private final Vector3d scratchWorld = new Vector3d();
    private final Vector3f scratchMove = new Vector3f();

    /** Bottom-face contacts this solve found on the deck. Solve thread. */
    private int supportContacts;

    @Nullable
    private AbstractVehicle supporting;

    /** Hull world rotation localRotation was derived from; turns after refresh re-derive it. */
    private final Quaternionf derivedFrom = new Quaternionf();

    public boolean active() {
        return carrier != null && boxes.size() > 0;
    }

    @Nullable
    public AbstractVehicle carrier() {
        return carrier;
    }

    /** Tick thread. True only when the last flushed solve's support matches this tick's refreshed carrier. */
    public boolean supported() {
        return supporting != null && supporting == carrier;
    }

    /** Carrier the last flushed solve stood on; may differ from this tick's refresh. Tick thread. */
    @Nullable
    public AbstractVehicle supportingCarrier() {
        return supporting;
    }

    /** Carrier frame. */
    public BoxBuffer deckBoxes() {
        return boxes;
    }

    /** Carrier frame -> world rotation. */
    public Quaternionf deckRotation() {
        return rotation;
    }

    public double pivotX() {
        return pivotX;
    }

    public double pivotY() {
        return pivotY;
    }

    public double pivotZ() {
        return pivotZ;
    }

    /** Solve: bottom contacts found on the deck. */
    public void supportContacts(int count) {
        supportContacts = count;
    }

    /** Tick thread, after a solve is applied. */
    public void publish() {
        supporting = supportContacts > 0 ? carrier : null;
    }

    public void clear() {
        reset();
        supporting = null;
    }

    private void reset() {
        carrier = null;
        boxes.clear();
        supportContacts = 0;
        // Frame changes per bind => next derive must run.
        derivedFrom.set(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    }

    /**
     * Picks this tick's carrier and freezes everything a solve will read from it. Tick thread only.
     * @param gather the region the tick's physics may reach, in world space
     * @return whether a carrier is in range
     */
    public boolean refresh(AbstractVehicle vehicle, AABB gather) {
        reset();
        // Client vehicles may tick before display data arrives and have no hull yet.
        if (!AllConfigs.Cached.carrierDecks || !vehicle.collision
                || vehicle.getMainCubeOBB() == null
                || !CarrierDecks.any(vehicle.level())) {
            return false;
        }
        // Most bound overlap first; next candidate when one has no deck box in reach.
        List<AbstractVehicle> candidates = CarrierDecks.overlapping(vehicle, gather);
        for (int i = 0, size = candidates.size(); i < size; i++) {
            if (bind(vehicle, candidates.get(i), gather)) {
                return true;
            }
        }
        return false;
    }

    private boolean bind(AbstractVehicle vehicle, AbstractVehicle found, AABB gather) {
        DeckSnapshot deck = found.deckSnapshot();
        if (deck.deckCount() == 0) {
            return false;
        }
        rotation.set(deck.rotation());
        inverse.set(deck.inverse());
        pivotX = deck.pivotX();
        pivotY = deck.pivotY();
        pivotZ = deck.pivotZ();

        // Transform the gather bound through the frame change.
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            DeckFrame.toLocal(inverse, pivotX, pivotY, pivotZ,
                    (i & 1) == 0 ? gather.minX : gather.maxX,
                    (i & 2) == 0 ? gather.minY : gather.maxY,
                    (i & 4) == 0 ? gather.minZ : gather.maxZ, scratchLocal);
            if (scratchLocal.x < minX) minX = scratchLocal.x;
            if (scratchLocal.y < minY) minY = scratchLocal.y;
            if (scratchLocal.z < minZ) minZ = scratchLocal.z;
            if (scratchLocal.x > maxX) maxX = scratchLocal.x;
            if (scratchLocal.y > maxY) maxY = scratchLocal.y;
            if (scratchLocal.z > maxZ) maxZ = scratchLocal.z;
        }
        DeckFrame.select(deck.deckBoxes(), deck.deckCount(),
                minX - SELECT_MARGIN, minY - SELECT_MARGIN, minZ - SELECT_MARGIN,
                maxX + SELECT_MARGIN, maxY + SELECT_MARGIN, maxZ + SELECT_MARGIN,
                boxes::add);
        if (boxes.isEmpty()) {
            return false;
        }

        derive(vehicle.getMainCubeOBB().obb().rotation());
        carrier = found;
        return true;
    }

    /** Hull rotation in the carrier's frame. Substeps only translate; a turn between refresh and solve re-derives. */
    private void derive(Quaternionf worldRotation) {
        if (worldRotation.equals(derivedFrom)) {
            return;
        }
        derivedFrom.set(worldRotation);
        DeckFrame.toLocalRotation(inverse, worldRotation, localRotation);
        localFrame.set(localRotation);
        localHull.rotation().set(localRotation);
        localAxes = localHull.getAxes();
    }

    /**
     * How much of a cast the deck allows, as a fraction in [0, 1].
     * Combined with the world cast result, the hull stops at the nearer contact.
     */
    public double timeOfImpact(OBB worldHull, double moveX, double moveY, double moveZ) {
        if (!active()) {
            return 1.0;
        }
        derive(worldHull.rotation());
        // Full precision: a deck contact is meaningless if the hull's own position was rounded
        // to a float grid before it was rotated into the carrier's frame.
        DeckFrame.toLocal(inverse, pivotX, pivotY, pivotZ,
                worldHull.centerX(), worldHull.centerY(), worldHull.centerZ(), scratchLocal);
        localHull.setCenter(scratchLocal.x, scratchLocal.y, scratchLocal.z);
        localHull.extents().set(worldHull.extents());
        // Transform movement to the carrier's frame.
        scratchMove.set((float) moveX, (float) moveY, (float) moveZ);
        inverse.transform(scratchMove);
        return SweptHull.timeOfImpact(localHull, boxes,
                scratchMove.x, scratchMove.y, scratchMove.z, null, localFrame);
    }

    /**
     * Appends the deck's contacts for this pose to out.
     * Runs synthesis in the carrier's frame and repairs contact positions to world space.
     * @param pose the hull's world OBB at the pose being sampled
     */
    public void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] worldAxes,
                        @Nullable ContactSynthesis.ContactPool pool,
                        List<VehicleCubeOBB.CubePoint> out) {
        supportContacts = 0;
        if (!active()) {
            return;
        }
        derive(pose.rotation());
        // Full precision, as above.
        DeckFrame.toLocal(inverse, pivotX, pivotY, pivotZ,
                pose.centerX(), pose.centerY(), pose.centerZ(), scratchLocal);
        localHull.setCenter(scratchLocal.x, scratchLocal.y, scratchLocal.z);
        localHull.extents().set(pose.extents());

        int before = out.size();
        ContactSynthesis.collect(hull, localHull, localAxes, boxes, DECK_RESOLVER, pool, out);
        for (int i = before, size = out.size(); i < size; i++) {
            VehicleCubeOBB.CubePoint point = out.get(i);
            VehicleCubeOBB.CubePointContext context = point.cubePointContext;
            // Transform contact position and surface height to world space.
            Vector3f local = point.cachedWorldPos();
            DeckFrame.toWorld(rotation, pivotX, pivotY, pivotZ,
                    local.x, context.surfaceY(), local.z, scratchWorld);
            context.setSurfaceY(scratchWorld.y);
            point.worldPos(pose, worldAxes);
            if (point.cubeFace() == VehicleCubeOBB.CubeFace.BOTTOM) {
                supportContacts++;
            }
        }
    }

    /**
     * Probes a single point against the deck.
     * @param worldPos the point's world position
     * @return true when the deck is at that point, with the point's context filled in
     */
    public boolean contactAt(VehicleCubeOBB.CubePoint point, Vector3f worldPos) {
        if (!active()) {
            return false;
        }
        DeckFrame.toLocal(inverse, pivotX, pivotY, pivotZ,
                worldPos.x, worldPos.y, worldPos.z, scratchLocal);
        double x = scratchLocal.x;
        double y = scratchLocal.y;
        double z = scratchLocal.z;
        for (int i = 0, size = boxes.size(); i < size; i++) {
            // Use the same speculative margin as the sweep, or the probe finds nothing.
            double margin = ContactSynthesis.CONTACT_MARGIN;
            if (x < boxes.minX(i) - margin || x >= boxes.maxX(i) + margin
                    || y < boxes.minY(i) - margin || y >= boxes.maxY(i) + margin
                    || z < boxes.minZ(i) - margin || z >= boxes.maxZ(i) + margin) {
                continue;
            }
            point.cubePointContext.clearCell();
            point.cubePointContext.setBlockState(null);
            DeckFrame.toWorld(rotation, pivotX, pivotY, pivotZ, x, boxes.maxY(i), z, scratchWorld);
            point.cubePointContext.setSurfaceY(scratchWorld.y);
            if (point.cubeFace() == VehicleCubeOBB.CubeFace.BOTTOM) {
                supportContacts++;
            }
            return true;
        }
        return false;
    }

    /**
     * Whether a trial pose would put the hull inside the deck.
     * Builds its own SAT frame because the pose being tested has not been adopted yet.
     */
    public boolean overlaps(OBB worldHull) {
        if (!active()) {
            return false;
        }
        // Full precision, as above.
        DeckFrame.toLocal(inverse, pivotX, pivotY, pivotZ,
                worldHull.centerX(), worldHull.centerY(), worldHull.centerZ(), scratchLocal);
        turnHull.setCenter(scratchLocal.x, scratchLocal.y, scratchLocal.z);
        turnHull.extents().set(worldHull.extents());
        DeckFrame.toLocalRotation(inverse, worldHull.rotation(), turnHull.rotation());
        return SweptHull.firstOverlappingBox(turnHull, boxes) >= 0;
    }

    /**
     * Fills in a deck contact with no block state.
     * The surface is the box's own top, transformed to world space by collect.
     */
    private static final ContactSynthesis.ContactResolver DECK_RESOLVER = (point, worldPos, box) -> {
        point.cubePointContext.clearCell();
        point.cubePointContext.setBlockState(null);
        point.cubePointContext.setSurfaceY(box.maxY);
        return true;
    };

}
