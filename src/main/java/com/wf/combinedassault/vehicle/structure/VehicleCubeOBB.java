package com.wf.combinedassault.vehicle.structure;

import com.wf.wflib.util.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

public class VehicleCubeOBB {

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
    private final List<CubePoint> cubePoints;
    private final List<CubePoint> attachedPoints = new ArrayList<>();
    private boolean pointsInitialized;
    private final Vector3f localCenter = new Vector3f();
    private final Quaternionf localRotation = new Quaternionf();
    public EnumMap<CubeFace, List<CubePoint>> cubePointsByFace = new EnumMap<>(CubeFace.class);
    public CubePoint bottomPoint;
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
        this.cubePoints = new ArrayList<>();
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
        this.cubePoints = new ArrayList<>();
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
        this.cubePoints = new ArrayList<>();
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
    public ConvexHull hull() {
        return hull;
    }

    /** This piece's tag and whether it is solid. Never null. */
    public HullTag tag() {
        return tag;
    }

    /** Whether this piece is part of the vehicle's physical surface; see {@link HullTag#solid()}. */
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
        cubePoints.clear();
        cubePointsByFace.clear();
        attachedPoints.clear();
        pointsInitialized = false;
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
    public Vector3f localCenter() {
        return localCenter;
    }

    /**
     * Orientation of this cube in vehicle-local space. Read-only
     */
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
        for (CubeFace face : CubeFace.values()) {
            cubePointsByFace.put(face, new ArrayList<>());
        }
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

    private void ensurePoints() {
        if (pointsInitialized) {
            return;
        }
        pointsInitialized = true;
        initCubePoints();
    }

    public void initCubePoints() {
        float gap = 0.1f;
        float slack = 0.1f;
        float offset = POINT_OFFSET;
        float x1 = -obb.extents().x - gap;
        float x2 = obb.extents().x + gap;
        float y1 = -obb.extents().y - offset;
        float y2 = obb.extents().y + gap;
        float z1 = -obb.extents().z - gap;
        float z2 = obb.extents().z + gap;
        CubePoint cubePoint;
        for (float x = x1; x <= x2 + slack; x += spaceX) {
            for (float y = y1; y <= y2 + slack; y += spaceY) {
                cubePoint = new CubePoint(this, new Vector3f(x, y, obb.extents().z + offset), CubeFace.FRONT);
                cubePointsByFace.get(CubeFace.FRONT).add(cubePoint);
                cubePoints.add(cubePoint);
                cubePoint = new CubePoint(this, new Vector3f(x, y, -obb.extents().z - offset), CubeFace.BACK);
                cubePointsByFace.get(CubeFace.BACK).add(cubePoint);
                cubePoints.add(cubePoint);
            }
        }
        for (float y = y1; y <= y2 + slack; y += spaceY) {
            for (float z = z1; z <= z2 + slack; z += spaceZ) {
                cubePoint = new CubePoint(this, new Vector3f(obb.extents().x + offset, y, z), CubeFace.LEFT);
                cubePointsByFace.get(CubeFace.LEFT).add(cubePoint);
                cubePoints.add(cubePoint);
                cubePoint = new CubePoint(this, new Vector3f(-obb.extents().x - offset, y, z), CubeFace.RIGHT);
                cubePointsByFace.get(CubeFace.RIGHT).add(cubePoint);
                cubePoints.add(cubePoint);
            }
        }
        for (float x = x1; x <= x2 + slack; x += spaceX) {
            for (float z = z1; z <= z2 + slack; z += spaceZ) {
                cubePoint = new CubePoint(this, new Vector3f(x, obb.extents().y + offset, z), CubeFace.TOP);
                cubePointsByFace.get(CubeFace.TOP).add(cubePoint);
                cubePoints.add(cubePoint);
                cubePoint = new CubePoint(this, new Vector3f(x, -obb.extents().y - offset, z), CubeFace.BOTTOM);
                cubePointsByFace.get(CubeFace.BOTTOM).add(cubePoint);
                cubePoints.add(cubePoint);
            }
        }
        initBottomPoint();
    }

    public void initBottomPoint() {
        ensurePoints();
        List<CubePoint> bottomPoints = cubePointsByFace.get(CubeFace.BOTTOM);
        if (bottomPoints == null || bottomPoints.isEmpty()) {
            return;
        }
        bottomPoints.sort(Comparator.comparingDouble(p -> p.obbLocalPos().y));
        bottomPoint = bottomPoints.get(0);
    }

    public Vec3 offset() {
        return offset;
    }

    public Vec3 center(AbstractVehicle vehicle) {
        return vehicle.position().add(offset());
    }

    public OBB obb() {
        return obb;
    }

    public Quaternionf selfRot() {
        return group.rotation;
    }

    public List<CubePoint> cubePoints() {
        ensurePoints();
        return cubePoints;
    }

    /**
     * Adds a sample point a part owns, such as a landing gear leg reaching below the hull.
     */
    public void attachPoint(CubePoint point) {
        ensurePoints();
        cubePoints.add(point);
        cubePointsByFace.get(point.cubeFace()).add(point);
        attachedPoints.add(point);
    }

    /**
     * Sample points attached by parts, distinct from those initialized on the cube surface.
     */
    public List<CubePoint> attachedPoints() {
        return attachedPoints;
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

    public static class CubePoint {

        private VehicleCubeOBB vehicleCubeOBB;
        private final Vector3f obbLocalPos;
        private Vector3f worldPos;
        private CubeFace cubeFace;
        /**
         * Outward normal of the surface this contact sits on, in the cube's frame.
         *
         * <p>For a box this is the face's own axis and nothing about the arithmetic changes. For a
         * hull it is the plane the sample was placed on, which is the whole reason a sloped plate
         * deflects rather than stopping a vehicle dead: the impulse cancels velocity along this,
         * not along whichever body axis the face happened to be closest to.
         */
        private final Vector3f normal = new Vector3f();
        public CubePointContext cubePointContext;

        /**
         * World position at the hull centre's precision. The float mirror above is what most
         * callers read, but anything that turns a contact into a block coordinate uses these:
         * at a million blocks out a float ordinate lands on a grid coarser than the contact margin.
         */
        private double wx, wy, wz;

        public CubePoint(VehicleCubeOBB vehicleCubeOBB, Vector3f obbLocalPos, CubeFace cubeFace) {
            this(vehicleCubeOBB, obbLocalPos, cubeFace, cubeFace.normal());
        }

        public CubePoint(VehicleCubeOBB vehicleCubeOBB, Vector3f obbLocalPos, CubeFace cubeFace,
                         Vector3f normal) {
            this.vehicleCubeOBB = vehicleCubeOBB;
            this.obbLocalPos = obbLocalPos;
            this.cubeFace = cubeFace;
            this.normal.set(normal);
            this.cubePointContext = new CubePointContext();
        }

        /** Re-aims a pooled point at a new contact, clearing whatever the last one touched. */
        public void reuse(VehicleCubeOBB hull, float lx, float ly, float lz, CubeFace face) {
            reuse(hull, lx, ly, lz, face, face.normal());
        }

        public void reuse(VehicleCubeOBB hull, float lx, float ly, float lz, CubeFace face,
                          Vector3f faceNormal) {
            this.vehicleCubeOBB = hull;
            this.obbLocalPos.set(lx, ly, lz);
            this.cubeFace = face;
            this.normal.set(faceNormal);
            this.cubePointContext.reset();
        }

        public Vector3f normal() {
            return normal;
        }

        /**
         * Rewrites this contact into the frame of the piece a solve is built around.
         *
         * <p>A compound vehicle samples each of its pieces in that piece's own frame, and everything
         * downstream measures a contact against the physics hull: the climb skirt is a height in it,
         * the belly test is its own underside, the impulse arm runs from its centre. So a contact
         * found on another piece is rotated and shifted into the hull's frame the moment it is known
         * to be real, and the face bucket is taken again from the normal it ends up with.
         *
         * <p>The world position is untouched, because it does not change: this is the same point
         * described from somewhere else.
         *
         * @param owner    the piece the solve is built around
         * @param rotation the sampled piece's orientation in that piece's frame
         * @param offset   the sampled piece's centre in that piece's frame
         */
        public void remap(VehicleCubeOBB owner, Quaternionf rotation, Vector3f offset) {
            rotation.transform(obbLocalPos).add(offset);
            rotation.transform(normal);
            this.vehicleCubeOBB = owner;
            this.cubeFace = CubeFace.of(normal.x, normal.y, normal.z);
        }

        public Vector3f obbLocalPos() {
            return obbLocalPos;
        }

        public Vector3f worldPos(Vector3f[] axes) {
            return worldPos(vehicleCubeOBB.obb, axes == null ? vehicleCubeOBB.obb.getAxes() : axes);
        }


        public Vector3f worldPos(OBB pose, Vector3f[] axes) {
            if (worldPos == null) {
                worldPos = new Vector3f();
            }
            float lx = obbLocalPos.x, ly = obbLocalPos.y, lz = obbLocalPos.z;
            wx = pose.centerX() + (axes[0].x * lx + axes[1].x * ly + axes[2].x * lz);
            wy = pose.centerY() + (axes[0].y * lx + axes[1].y * ly + axes[2].y * lz);
            wz = pose.centerZ() + (axes[0].z * lx + axes[1].z * ly + axes[2].z * lz);
            worldPos.set((float) wx, (float) wy, (float) wz);
            return worldPos;
        }

        public Vector3f cachedWorldPos() {
            return worldPos;
        }

        public double worldX() {
            return wx;
        }

        public double worldY() {
            return wy;
        }

        public double worldZ() {
            return wz;
        }

        public CubeFace cubeFace() {
            return cubeFace;
        }

    }

    public static class CubePointContext {

        public static final long NO_CELL = Long.MIN_VALUE;
        private long cellPos = NO_CELL;
        private boolean worldCell;
        private BlockState blockState;
        private double surfaceY = Double.NaN;

        /** The contacted cell packed as a long, or NO_CELL if none. */
        public long cellPos() {
            return cellPos;
        }

        /** True when a cell is set and it names a real block in this level. */
        public boolean hasWorldCell() {
            return worldCell && cellPos != NO_CELL;
        }

        public boolean hasCell() {
            return cellPos != NO_CELL;
        }

        public int cellY() {
            return BlockPos.getY(cellPos);
        }

        /** Records a contact against a real block in this level. */
        public void setWorldCell(int x, int y, int z) {
            this.cellPos = BlockPos.asLong(x, y, z);
            this.worldCell = true;
        }

        public void clearCell() {
            this.cellPos = NO_CELL;
            this.worldCell = false;
        }

        /** Wipes everything the last contact left behind, for points that outlive one tick. */
        public void reset() {
            clearCell();
            this.blockState = null;
            this.surfaceY = Double.NaN;
        }


        @Nullable
        public Vec3 blockPos() {
            if (cellPos == NO_CELL) {
                return null;
            }
            return new Vec3(BlockPos.getX(cellPos) + 0.5, BlockPos.getY(cellPos),
                    BlockPos.getZ(cellPos) + 0.5);
        }

        public BlockState blockState() {
            return blockState;
        }

        public void setBlockState(BlockState blockState) {
            this.blockState = blockState;
        }

        /**
         * World height of the top of the geometry this point is touching, or NaN if unknown.
         */
        public double surfaceY() {
            return surfaceY;
        }

        public void setSurfaceY(double surfaceY) {
            this.surfaceY = surfaceY;
        }

    }

    public enum CubeFace {

        FRONT(0, 0, 1), BACK(0, 0, -1), LEFT(1, 0, 0), RIGHT(-1, 0, 0),
        TOP(0, 1, 0), BOTTOM(0, -1, 0);

        private final Vector3f normal;

        CubeFace(float x, float y, float z) {
            this.normal = new Vector3f(x, y, z);
        }

        /** This face's outward normal in the cube's frame. Shared and never written to. */
        public Vector3f normal() {
            return normal;
        }

        /**
         * The face an arbitrary outward normal reads as.
         *
         * <p>A hull face points wherever the author drew it, but the gates around contacts are
         * written in terms of six sides: a climb skirt applies to the sides and not the roof, a
         * belly does not bounce, and the spin gather buckets by side. Taking the dominant axis
         * keeps every one of those meaning what it meant, while the impulse itself uses the real
         * normal.
         */
        public static CubeFace of(float x, float y, float z) {
            float ax = Math.abs(x);
            float ay = Math.abs(y);
            float az = Math.abs(z);
            if (ay >= ax && ay >= az) {
                return y >= 0 ? TOP : BOTTOM;
            }
            if (az >= ax) {
                return z >= 0 ? FRONT : BACK;
            }
            return x >= 0 ? LEFT : RIGHT;
        }

    }

}
