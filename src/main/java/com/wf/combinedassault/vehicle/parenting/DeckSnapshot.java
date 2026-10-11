package com.wf.combinedassault.vehicle.parenting;

import org.joml.Quaternionf;

/**
 * One tick's view of a vehicle as its riders see it: pivot position, rotation, and local boxes
 * they stand on, published as a single object. Position and rotation always read from the same
 * instance, which parallel physics requires; safe to hold only within the call that obtained it.
 */
public final class DeckSnapshot {

    /** Default snapshot for vehicles with no walkable geometry; riders never see null. */
    public static final DeckSnapshot EMPTY = new DeckSnapshot();

    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf inverse = new Quaternionf();

    private double pivotX;
    private double pivotY;
    private double pivotZ;
    private float yaw;

    private float[] boxes = new float[0];
    private int count;

    /**
     * Landing-surface subset of boxes, same frame. Separate array rather than a mask, so riders and
     * sweep casts walk only the data they need. Empty when no deck bone is declared.
     */
    private float[] deckBoxes = new float[0];
    private int deckCount;

    /** Vehicle-local frame's origin in world space. */
    public double pivotX() {
        return pivotX;
    }

    public double pivotY() {
        return pivotY;
    }

    public double pivotZ() {
        return pivotZ;
    }

    /** Hull orientation; inverse() is its cached conjugate. */
    public Quaternionf rotation() {
        return rotation;
    }

    public Quaternionf inverse() {
        return inverse;
    }

    /** Vehicle yaw when taken; riders turn by the difference between snapshots. */
    public float yaw() {
        return yaw;
    }

    /** Vehicle-local axis-aligned boxes, six floats per cube. */
    public float[] boxes() {
        return boxes;
    }

    public int count() {
        return count;
    }

    public float[] deckBoxes() {
        return deckBoxes;
    }

    public int deckCount() {
        return deckCount;
    }

    /** Grown to fit the hull; only the writer may touch it before publish. */
    public float[] boxBuffer(int cubes) {
        if (boxes.length < cubes * 6) {
            boxes = new float[cubes * 6];
        }
        return boxes;
    }

    public float[] deckBoxBuffer(int cubes) {
        if (deckBoxes.length < cubes * 6) {
            deckBoxes = new float[cubes * 6];
        }
        return deckBoxes;
    }

    public void set(Quaternionf hullRotation, float hullYaw,
                    double originX, double originY, double originZ, int boxCount, int deckBoxCount) {
        this.rotation.set(hullRotation);
        // Cached: riders need the conjugate on every clip.
        hullRotation.conjugate(this.inverse);
        this.yaw = hullYaw;
        this.pivotX = originX;
        this.pivotY = originY;
        this.pivotZ = originZ;
        this.count = boxCount;
        this.deckCount = deckBoxCount;
    }

}
