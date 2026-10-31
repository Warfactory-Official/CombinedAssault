package com.wf.combinedassault.dynamics.structure;

import com.wf.wflib.physics.api.HullPiece;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.ConvexHull;
import com.wf.wflib.physics.api.HullTag;


public class VehicleCubeOBB implements HullPiece {

    private static final int MAX_SAMPLES_PER_AXIS = 24;

    /** How far outside its face a sample point sits for contact detection. */
    public static final float POINT_OFFSET = 0.001f;
    /**
     * Headroom above the step height, in blocks. Must stay above the speculative contact margin,
     * or a contact generated before overlap reads as a wall instead of a step.
     */
    private static final double RIDE_STEP_MARGIN = 0.06;

    private final OBB obb;
    /**
     * This cube's real shape, or null when the box is the shape.
     *
     * <p>Held in the cube's own frame, centred where the OBB is centred, so {@link #update} poses it
     * by the same position and rotation with no extra work. The OBB is then this hull's tightest box,
     * which is what a broad-phase test wants anyway.
     */
    @Nullable
    private ConvexHull hull;
    /** What this piece is. Never null: an untagged cube gets {@link HullTag#DEFAULT}. */
    private HullTag tag = HullTag.DEFAULT;
    public VehicleCubeGroup group;
    private boolean deck;
    private final Vector3f localCenter = new Vector3f();
    private final Quaternionf localRotation = new Quaternionf();
    private Vec3 offset = Vec3.ZERO;
    public double x;
    public double y;
    public double z;
    public double height;
    public double width;
    public double depth;
    public double spaceX;
    public double spaceY;
    public double spaceZ;
    public Vec3 position;
    public Vec3 positionO;
    public Quaternionf rotation;
    public Quaternionf rotationO;

    public VehicleCubeOBB(OBB obb) {
        this.obb = obb;
        this.initSpacing();
        this.offset = new Vec3(obb.extents().x, obb.extents().y, obb.extents().z);
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.height = obb.extents().y * 2;
        this.width = obb.extents().x * 2;
        this.depth = obb.extents().z * 2;
    }

    public VehicleCubeOBB(OBB obb, VehicleCubeGroup group,
                          double x, double y, double z, double width, double height, double depth) {
        this.obb = obb;
        this.group = group;
        this.offset = group
                .globalTransform(new Vec3(x + width / 2, y + height / 2, z + depth / 2), false)
                .offset();
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
        this.depth = depth;
        group.addCubeOBB(this);
        this.initSpacing();
    }

    public VehicleCubeOBB(VehicleCubeOBB origin) {
        this.obb = origin.obb.copy();
        this.group = origin.group;
        this.hull = origin.hull;
        this.tag = origin.tag;
        this.deck = origin.deck;
        this.initSpacing();
        this.offset = origin.offset;
        this.x = origin.x;
        this.y = origin.y;
        this.z = origin.z;
        this.height = origin.height;
        this.width = origin.width;
        this.depth = origin.depth;
    }

    /**
     * Builds a cube from a convex hull authored in the vehicle's own frame.
     *
     * <p>Carried into the group's frame and centred; the reported box is the hull's bound.
     */
    public static VehicleCubeOBB init(VehicleCubeGroup group, ConvexHull vehicleLocal, boolean deck) {
        return init(group, vehicleLocal, deck, HullTag.DEFAULT);
    }

    public static VehicleCubeOBB init(VehicleCubeGroup group, ConvexHull vehicleLocal, boolean deck,
                                      HullTag tag) {
        VehicleCubeGroup.GlobalTransform rest = group.globalTransform();
        Quaternionf inverse = new Quaternionf(rest.rotation()).conjugate();
        Vector3f shift = inverse.transform(new Vector3f(
                (float) -rest.offset().x, (float) -rest.offset().y, (float) -rest.offset().z));
        ConvexHull inGroup = vehicleLocal.transformed(inverse, shift);

        double width = inGroup.maxX() - inGroup.minX();
        double height = inGroup.maxY() - inGroup.minY();
        double depth = inGroup.maxZ() - inGroup.minZ();
        Vector3f centre = new Vector3f(
                (inGroup.minX() + inGroup.maxX()) * 0.5f,
                (inGroup.minY() + inGroup.maxY()) * 0.5f,
                (inGroup.minZ() + inGroup.maxZ()) * 0.5f);

        OBB obb = new OBB(Vec3.ZERO.toVector3f(),
                new Vector3f((float) width / 2, (float) height / 2, (float) depth / 2),
                new Quaternionf(group.rotation));
        VehicleCubeOBB cubeOBB = new VehicleCubeOBB(obb, group,
                inGroup.minX(), inGroup.minY(), inGroup.minZ(), width, height, depth);
        cubeOBB.hull = inGroup.transformed(new Quaternionf(), centre.negate());
        cubeOBB.deck = deck;
        cubeOBB.tag = tag;
        return cubeOBB;
    }

    /** This cube's real shape, or null when it is simply the box it reports. */
    @Nullable
    @Override
    public ConvexHull hull() {
        return hull;
    }

    /** This piece's tag and whether it is solid. Never null. */
    @Override
    public HullTag tag() {
        return tag;
    }

    /** Whether this piece is part of the vehicle's physical surface; see {@link HullTag#solid()}. */
    @Override
    public boolean isSolid() {
        return tag.solid();
    }

    public boolean hasHull() {
        return hull != null;
    }

    /** Whether this cube is declared landing surface for other vehicles. */
    public boolean isDeck() {
        return deck;
    }

    public void rebuild() {
        offset = group
                .globalTransform(new Vec3(x + width / 2, y + height / 2, z + depth / 2), false)
                .offset();
        obb.setExtents(new Vector3f((float) (width / 2), (float) (height / 2), (float) (depth / 2)));
        initSpacing();
    }

    /**
     * Refreshes this cube's pose by walking the parent chain.
     */
    public void update(AbstractVehicle vehicle) {
        VehicleCubeGroup.GlobalTransform globalTransform = group.globalTransform();
        update(vehicle, vehicle.rotYXZ(), globalTransform.offset().toVector3f(), globalTransform.rotation());
    }

    /**
     * Refreshes this cube's local and world pose.
     */
    public void update(AbstractVehicle vehicle, Quaternionf vehicleRotation, Vector3f groupOffset, Quaternionf groupRotation) {
        positionO = position;
        rotationO = rotation;

        localCenter.set((float) (x + width / 2), (float) (y + height / 2), (float) (z + depth / 2));
        groupRotation.transform(localCenter);
        localCenter.add(groupOffset);
        localCenter.sub((float) vehicle.centerOffset.x, (float) vehicle.centerOffset.y, (float) vehicle.centerOffset.z);
        localRotation.set(groupRotation);


        Vector3f center = obb.center();
        center.set(localCenter);
        vehicleRotation.transform(center);
        position = new Vec3(
                vehicle.getX() + vehicle.centerOffset.x + center.x,
                vehicle.getY() + vehicle.centerOffset.y + center.y,
                vehicle.getZ() + vehicle.centerOffset.z + center.z);
        // The pose is known in doubles here; hand the obb that rather than a narrowed copy.
        obb.setCenter(position);

        rotation = vehicleRotation.mul(groupRotation, new Quaternionf());
        obb.setRotation(rotation);
    }

    public void translate(float fx, float fy, float fz, double dx, double dy, double dz) {
        positionO = position;
        rotationO = rotation;
        if (position != null) {
            position = position.add(dx, dy, dz);
        }
        obb.translate(dx, dy, dz);
    }

    /**
     * Centre of this cube in vehicle-local space. Read-only
     */
    @Override
    public Vector3f localCenter() {
        return localCenter;
    }

    /**
     * Orientation of this cube in vehicle-local space. Read-only
     */
    @Override
    public Quaternionf localRotation() {
        return localRotation;
    }

    public static VehicleCubeOBB defaultCube() {
        return new VehicleCubeOBB(new OBB(Vec3.ZERO.toVector3f(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
    }

    private void initSpacing() {
        float gap = 0.1f;
        float offset = POINT_OFFSET;
        float x1 = -obb.extents().x - gap;
        float x2 = obb.extents().x + gap;
        float y1 = -obb.extents().y - offset;
        float y2 = obb.extents().y + gap;
        float z1 = -obb.extents().z - gap;
        float z2 = obb.extents().z + gap;
        spaceX = spacing(x2 - x1);
        spaceY = spacing(y2 - y1);
        spaceZ = spacing(z2 - z1);
    }

    /**
     * Divides span into at most MAX_SAMPLES_PER_AXIS equal segments.
     */
    private static double spacing(double span) {
        double step = Math.max(1.0, span / MAX_SAMPLES_PER_AXIS);
        return span / Math.max(1, Math.ceil(span / step));
    }



    /**
     * Height above the hull underside, in local coordinates, below which a contact is treated as
     * something to ride over rather than something to stop against. Scales with the hull, so
     * bigger vehicles ride over bigger bumps.
     */
    public double climbSkirt() {
        return -obb.extents().y - POINT_OFFSET + 2 * spaceY;
    }

    /**
     * The climb skirt clamped to what this vehicle can actually step up. Anchored to the hull
     * underside, never to a datum an obstacle can raise.
     */
    public double rideSkirt(double maxUpStep) {
        return Math.min(climbSkirt(), -obb.extents().y + maxUpStep + RIDE_STEP_MARGIN);
    }

    public Vec3 offset() {
        return offset;
    }

    public Vec3 center(AbstractVehicle vehicle) {
        return vehicle.position().add(offset());
    }

    @Override
    public OBB obb() {
        return obb;
    }

    public Quaternionf selfRot() {
        return group.rotation;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public double getHeight() {
        return height;
    }

    public double getWidth() {
        return width;
    }

    public double getDepth() {
        return depth;
    }

    public double volume() {
        return width * height * depth;
    }

}
