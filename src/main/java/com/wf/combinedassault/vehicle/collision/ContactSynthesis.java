package com.wf.combinedassault.vehicle.collision;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.structure.ConvexHull;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Contacts from real per-box geometry, not a fixed sampling grid: only the area that actually
 * touches. Hull-local position + face id; multiple samples/box preserve footprint extent for the
 * support polygon.
 */
public final class ContactSynthesis {

    /** Samples per axis across one footprint; a box contributes at most 16 contacts. */
    private static final int MAX_SAMPLES_PER_AXIS = 4;

    /** Outward offset applied to contact points on the hull face. */
    private static final float FACE_OFFSET = 0.001f;

    /** Tangential margin for footprint sampling; matches the (legacy) grid's edge coverage. */
    private static final float FACE_GAP = 0.1f;

    /** A fence/wall reaches half a block above its own cell; walk down this far to find the owning cell. */
    private static final int OWNER_SEARCH_DEPTH = 3;

    /** Blocks. Contacts generated before overlap, to avoid a sweep/contact deadlock when held just clear of ground. */
    public static final double CONTACT_MARGIN = 0.05;

    /** Containment test is half-open; an extreme sample placed exactly on the far edge would land in the next cell and be dropped. */
    private static final float EDGE_INSET = 0.001f;

    /** Support polygon needs only the extremes; block-breaking needs cells between them too. */
    private static final int MAX_INTERIOR_SAMPLES = 2;

    /** Per-vehicle, refilled each tick. Points recycled on next {@link #reset}; never retain one past the solve. */
    public static final class ContactPool {

        private final List<VehicleCubeOBB.CubePoint> points = new ArrayList<>();
        private int used;

        /** Hands every point back. Call once per solve, before any contact is generated. */
        public void reset() {
            used = 0;
        }

        VehicleCubeOBB.CubePoint take(VehicleCubeOBB hull, float lx, float ly, float lz,
                                      VehicleCubeOBB.CubeFace face) {
            return take(hull, lx, ly, lz, face, face.normal());
        }

        VehicleCubeOBB.CubePoint take(VehicleCubeOBB hull, float lx, float ly, float lz,
                                      VehicleCubeOBB.CubeFace face, Vector3f normal) {
            if (used == points.size()) {
                points.add(new VehicleCubeOBB.CubePoint(hull, new Vector3f(), face));
            }
            VehicleCubeOBB.CubePoint point = points.get(used++);
            point.reuse(hull, lx, ly, lz, face, normal);
            return point;
        }

        /** Takes back the point handed out last, when it turned out to touch nothing. */
        void releaseLast() {
            if (used > 0) {
                used--;
            }
        }

    }

    private ContactSynthesis() {}

    /** Called once per point already proven inside the box. */
    @FunctionalInterface
    public interface ContactResolver {

        /**
         * @param worldPos caller-owned, reused; do not retain
         * @return true to keep the point, having filled in its cubePointContext
         */
        boolean resolve(VehicleCubeOBB.CubePoint point, Vector3f worldPos, AABB box);

    }

    public static ContactResolver blocks(ChunkCollisionCache.Cursor cursor) {
        return (point, worldPos, box) -> {
            int blockX = Mth.floor(Mth.clamp(point.worldX(), box.minX, box.maxX - 1.0e-6));
            int blockZ = Mth.floor(Mth.clamp(point.worldZ(), box.minZ, box.maxZ - 1.0e-6));
            int from = Mth.floor(Mth.clamp(point.worldY(), box.minY, box.maxY - 1.0e-6));
            int to = Math.max(Mth.floor(box.minY), from - OWNER_SEARCH_DEPTH);
            for (int blockY = from; blockY >= to; blockY--) {
                BlockState state = cursor.collisionAt(blockX, blockY, blockZ);
                if (state == null) {
                    continue;
                }
                point.cubePointContext.setWorldCell(blockX, blockY, blockZ);
                point.cubePointContext.setBlockState(state);
                point.cubePointContext.setSurfaceY(cursor.collisionTop(blockX, blockY, blockZ));
                return true;
            }
            return false;
        };
    }

    /** For the legacy grid query. */
    public static boolean resolveColumn(ChunkCollisionCache.Cursor cursor,
                                        VehicleCubeOBB.CubePoint point) {
        VehicleCubeOBB.CubePointContext context = point.cubePointContext;
        int blockX = Mth.floor(point.worldX());
        int blockZ = Mth.floor(point.worldZ());
        int blockY = Mth.floor(point.worldY());
        BlockState state = cursor.collisionAt(blockX, blockY, blockZ);
        if (state == null) {
            blockY--;
            state = cursor.collisionAt(blockX, blockY, blockZ);
            // CONTACT_MARGIN applies here too, or a sweep-held point reads as standing on nothing.
            if (state == null
                    || cursor.collisionTop(blockX, blockY, blockZ) + CONTACT_MARGIN <= point.worldY()) {
                return false;
            }
        }
        context.setWorldCell(blockX, blockY, blockZ);
        context.setBlockState(state);
        context.setSurfaceY(cursor.collisionTop(blockX, blockY, blockZ));
        return true;
    }

    /**
     * Compound bodies: a piece samples in its own frame; this remaps into the solve's hull frame.
     * Runs here (contact proven real, point still the pool's most recent) so a rejection can still
     * {@link ContactPool#releaseLast}.
     */
    @FunctionalInterface
    public interface ContactFilter {

        /**
         * @param point the contact, in the sampled piece's own frame; may be rewritten in place
         * @return true to keep it
         */
        boolean keep(VehicleCubeOBB.CubePoint point);

    }

    /** @param boxes world-space, overlapping the hull's bound */
    public static void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] axes, List<AABB> boxes,
                               ContactResolver resolver, List<VehicleCubeOBB.CubePoint> out) {
        BoxBuffer buffer = new BoxBuffer(boxes.size());
        buffer.addAll(boxes);
        collect(hull, pose, axes, buffer, resolver, null, out);
    }

    public static void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] axes, List<AABB> boxes,
                               ContactResolver resolver, @Nullable ContactPool pool,
                               List<VehicleCubeOBB.CubePoint> out) {
        collect(hull, pose, axes, boxes, resolver, pool, out, hull.climbSkirt(), null);
    }

    public static void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] axes, List<AABB> boxes,
                               ContactResolver resolver, @Nullable ContactPool pool,
                               List<VehicleCubeOBB.CubePoint> out, double skirt,
                               @Nullable ContactFilter filter) {
        BoxBuffer buffer = new BoxBuffer(boxes.size());
        buffer.addAll(boxes);
        collect(hull, pose, axes, buffer, resolver, pool, out, skirt, filter);
    }

    public static void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] axes, BoxBuffer boxes,
                               ContactResolver resolver, List<VehicleCubeOBB.CubePoint> out) {
        collect(hull, pose, axes, boxes, resolver, null, out);
    }

    /** @param pose shadow copy of the hull's OBB: its centre may be substep-advanced past the live OBB */
    public static void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] axes, BoxBuffer boxes,
                               ContactResolver resolver, @Nullable ContactPool pool,
                               List<VehicleCubeOBB.CubePoint> out) {
        collect(hull, pose, axes, boxes, resolver, pool, out, hull.climbSkirt(), null);
    }

    /**
     * @param skirt  sampled piece's own frame; for a compound piece this is the vehicle's skirt,
     *               not the piece's own (blocking height is vehicle-level). NaN: no skirt row.
     * @param filter null: keep contacts as sampled. Else last say + compound frame remap.
     */
    public static void collect(VehicleCubeOBB hull, OBB pose, Vector3f[] axes, BoxBuffer boxes,
                               ContactResolver resolver, @Nullable ContactPool pool,
                               List<VehicleCubeOBB.CubePoint> out, double skirt,
                               @Nullable ContactFilter filter) {
        if (hull.hasHull()) {
            collectOnHull(hull, hull.hull(), pose, axes, boxes, resolver, pool, out, skirt, filter);
            return;
        }
        OBB obb = pose;
        Vector3f extents = obb.extents();
        Scratch scratch = SCRATCH.get();
        Vector3f localMin = scratch.localMin;
        Vector3f localMax = scratch.localMax;
        Vector3f cornerLocal = scratch.cornerLocal;
        Vector3f local = scratch.local;
        float[] uSamples = scratch.uSamples;
        float[] vSamples = scratch.vSamples;
        // A hair above the plane rather than on it: PhysicsEngine compares against a double and
        // these are floats, so a sample sitting exactly on the boundary could round to the
        // ignored side and quietly undo the point of placing it.
        float skirtSample = (float) (skirt + FACE_OFFSET);

        // Hoisted out of the loop: identical for every box, and building it per box was three
        // quaternion transforms and four objects each time. The frame also precomputes the
        // absolute-term table once, so the per-box test below is bare arithmetic.
        OBB.SatFrame frame = scratch.frame.set(obb.rotation());
        // The centre in doubles, not the float mirror beside it: a million blocks out that mirror
        // is rounded to a grid coarser than the hull, and this is what decides whether a box is
        // offered to the sampler at all.
        double centreX = obb.centerX();
        double centreY = obb.centerY();
        double centreZ = obb.centerZ();
        for (int i = 0, size = boxes.size(); i < size; i++) {
            // Margin-grown bounds for the broad test below; true box kept separate for the resolver's clamp.
            double boxMinX = boxes.minX(i) - CONTACT_MARGIN;
            double boxMinY = boxes.minY(i) - CONTACT_MARGIN;
            double boxMinZ = boxes.minZ(i) - CONTACT_MARGIN;
            double boxMaxX = boxes.maxX(i) + CONTACT_MARGIN;
            double boxMaxY = boxes.maxY(i) + CONTACT_MARGIN;
            double boxMaxZ = boxes.maxZ(i) + CONTACT_MARGIN;
            if (!OBB.intersectsBox(frame, centreX, centreY, centreZ, extents,
                    boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ)) {
                continue;
            }
            // AABB built only for broad-phase survivors.
            AABB box = boxes.get(i);

            // Hull-local span; conservative under rotation, safe since contains() below is exact.
            localMin.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            localMax.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            for (int c = 0; c < 8; c++) {
                // Double difference formed before narrowing: float world coords lose precision far from origin.
                obb.worldToLocal(
                        (c & 1) == 0 ? boxMinX : boxMaxX,
                        (c & 2) == 0 ? boxMinY : boxMaxY,
                        (c & 4) == 0 ? boxMinZ : boxMaxZ, axes, cornerLocal);
                localMin.min(cornerLocal);
                localMax.max(cornerLocal);
            }

            int face = contactFace(obb, box, axes);
            int normalAxis = face >> 1;
            boolean positive = (face & 1) == 0;
            int uAxis = (normalAxis + 1) % 3;
            int vAxis = (normalAxis + 2) % 3;

            float uLimit = extents.get(uAxis) + FACE_GAP;
            float vLimit = extents.get(vAxis) + FACE_GAP;
            float uFrom = Math.max(localMin.get(uAxis), -uLimit);
            float uTo = Math.min(localMax.get(uAxis), uLimit);
            float vFrom = Math.max(localMin.get(vAxis), -vLimit);
            float vTo = Math.min(localMax.get(vAxis), vLimit);
            if (uFrom > uTo || vFrom > vTo) {
                continue;
            }

            float normalCoord = positive
                    ? extents.get(normalAxis) + FACE_OFFSET
                    : -extents.get(normalAxis) - FACE_OFFSET;
            // Face-plane sampling assumes the box lies beyond it; a sub-block box (slab/stair tread)
            // sits inside instead and every sample would miss. Slide onto the box's own span, then
            // a hair further so the half-open test still accepts it; bounded by that span, so this
            // can only move a sample onto real geometry.
            float nearest = Mth.clamp(normalCoord, localMin.get(normalAxis), localMax.get(normalAxis));
            if (nearest != normalCoord) {
                normalCoord = nearest > normalCoord ? nearest + FACE_OFFSET : nearest - FACE_OFFSET;
            }
            // uAxis==1: tangent is the hull's own Y, the climb-skirt axis; forcing a sample there
            // makes skirt detection a geometry question, not an accident of even spacing.
            int uSteps = axisSamples(uFrom, uTo, uAxis == 1 ? skirtSample : Float.NaN, uSamples);
            int vSteps = axisSamples(vFrom, vTo, vAxis == 1 ? skirtSample : Float.NaN, vSamples);

            for (int u = 0; u < uSteps; u++) {
                float uPos = uSamples[u];
                for (int v = 0; v < vSteps; v++) {
                    float vPos = vSamples[v];
                    local.set(0, 0, 0);
                    local.setComponent(normalAxis, normalCoord);
                    local.setComponent(uAxis, uPos);
                    local.setComponent(vAxis, vPos);

                    VehicleCubeOBB.CubePoint point = pool == null
                            ? new VehicleCubeOBB.CubePoint(hull, new Vector3f(local), FACES[face])
                            : pool.take(hull, local.x, local.y, local.z, FACES[face]);
                    Vector3f world = point.worldPos(obb, axes);
                    // contains() checked against grown bounds, not true box: a sample in the sweep's
                    // gap above a surface is exactly the contact that must survive. Testing the box
                    // (not just the cell) keeps the contact set a strict subset of real geometry.
                    if (!contains(point, boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ)
                            || !resolver.resolve(point, world, box)
                            || (filter != null && !filter.keep(point))) {
                        if (pool != null) {
                            pool.releaseLast();
                        }
                        continue;
                    }
                    out.add(point);
                }
            }
        }
    }

    /**
     * Hull-face analogue of the box path: face the box is most squarely outside of, a 2D frame in
     * its plane, sample the covered part of the polygon. Carries the face's real normal, not the
     * nearest body axis.
     */
    private static void collectOnHull(VehicleCubeOBB cube, ConvexHull hull, OBB obb, Vector3f[] axes,
                                      BoxBuffer boxes, ContactResolver resolver,
                                      @Nullable ContactPool pool, List<VehicleCubeOBB.CubePoint> out,
                                      double skirt, @Nullable ContactFilter filter) {
        if (boxes.isEmpty()) {
            return;
        }
        Vector3f extents = obb.extents();
        Scratch scratch = SCRATCH.get();
        Vector3f localMin = scratch.localMin;
        Vector3f localMax = scratch.localMax;
        Vector3f cornerLocal = scratch.cornerLocal;
        Vector3f local = scratch.local;
        Vector3f normal = scratch.normal;
        float[] uSamples = scratch.uSamples;
        float[] vSamples = scratch.vSamples;
        float skirtSample = (float) (skirt + FACE_OFFSET);

        // Every face's plane frame and its outline in that frame, worked out once. None of it depends
        // on the box being tested, and building it per box was the single largest cost in here.
        FaceFrames frames = FaceFrames.of(hull);

        OBB.SatFrame frame = scratch.frame.set(obb.rotation());
        // Double centre: float mirror loses precision far from origin, deciding box admission wrongly.
        double centreX = obb.centerX();
        double centreY = obb.centerY();
        double centreZ = obb.centerZ();
        float[] planes = hull.planes();

        for (int i = 0, size = boxes.size(); i < size; i++) {
            double boxMinX = boxes.minX(i) - CONTACT_MARGIN;
            double boxMinY = boxes.minY(i) - CONTACT_MARGIN;
            double boxMinZ = boxes.minZ(i) - CONTACT_MARGIN;
            double boxMaxX = boxes.maxX(i) + CONTACT_MARGIN;
            double boxMaxY = boxes.maxY(i) + CONTACT_MARGIN;
            double boxMaxZ = boxes.maxZ(i) + CONTACT_MARGIN;
            // Hull bound reject first: cheap, and hull lies inside it, so a reject here is a true reject.
            if (!OBB.intersectsBox(frame, centreX, centreY, centreZ, extents,
                    boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ)) {
                continue;
            }

            localMin.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            localMax.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            for (int c = 0; c < 8; c++) {
                obb.worldToLocal(
                        (c & 1) == 0 ? boxMinX : boxMaxX,
                        (c & 2) == 0 ? boxMinY : boxMaxY,
                        (c & 4) == 0 ? boxMinZ : boxMaxZ, axes, cornerLocal);
                localMin.min(cornerLocal);
                localMax.max(cornerLocal);
            }

            int face = hullFace(hull, localMin, localMax);
            if (face < 0) {
                continue;
            }
            normal.set(planes[face * 4], planes[face * 4 + 1], planes[face * 4 + 2]);
            float planeOffset = planes[face * 4 + 3];
            float ux = frames.ux[face], uy = frames.uy[face], uz = frames.uz[face];
            float vx = frames.vx[face], vy = frames.vy[face], vz = frames.vz[face];

            float boxMinU = low(localMin, localMax, ux, uy, uz);
            float boxMaxU = high(localMin, localMax, ux, uy, uz);
            float boxMinV = low(localMin, localMax, vx, vy, vz);
            float boxMaxV = high(localMin, localMax, vx, vy, vz);
            float boxMinN = low(localMin, localMax, normal.x, normal.y, normal.z);
            float boxMaxN = high(localMin, localMax, normal.x, normal.y, normal.z);
            float uFrom = Math.max(frames.minU[face] - FACE_GAP, boxMinU);
            float uTo = Math.min(frames.maxU[face] + FACE_GAP, boxMaxU);
            float vFrom = Math.max(frames.minV[face] - FACE_GAP, boxMinV);
            float vTo = Math.min(frames.maxV[face] + FACE_GAP, boxMaxV);
            if (uFrom > uTo || vFrom > vTo) {
                continue;
            }

            // On the face unless the box is inside the hull (slab/stair tread); then slide onto the
            // box, as the box path does.
            float normalCoord = planeOffset + FACE_OFFSET;
            float nearest = Mth.clamp(normalCoord, boxMinN, boxMaxN);
            if (nearest != normalCoord) {
                normalCoord = nearest > normalCoord ? nearest + FACE_OFFSET : nearest - FACE_OFFSET;
            }

            float skirtU = Math.abs(uy) > 1.0e-4f
                    ? (skirtSample - normal.y * normalCoord) / uy
                    : Float.NaN;
            int uSteps = axisSamples(uFrom, uTo, skirtU, uSamples);
            int vSteps = axisSamples(vFrom, vTo, Float.NaN, vSamples);
            VehicleCubeOBB.CubeFace bucket = frames.bucket[face];
            AABB box = boxes.get(i);

            for (int u = 0; u < uSteps; u++) {
                float uPos = uSamples[u];
                for (int v = 0; v < vSteps; v++) {
                    float vPos = vSamples[v];
                    if (!frames.inside(face, uPos, vPos)) {
                        continue;
                    }
                    local.set(normal).mul(normalCoord);
                    local.x += ux * uPos + vx * vPos;
                    local.y += uy * uPos + vy * vPos;
                    local.z += uz * uPos + vz * vPos;

                    VehicleCubeOBB.CubePoint point = pool == null
                            ? new VehicleCubeOBB.CubePoint(cube, new Vector3f(local), bucket, normal)
                            : pool.take(cube, local.x, local.y, local.z, bucket, normal);
                    Vector3f world = point.worldPos(obb, axes);
                    if (!contains(point, boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ)
                            || !resolver.resolve(point, world, box)
                            || (filter != null && !filter.keep(point))) {
                        if (pool != null) {
                            pool.releaseLast();
                        }
                        continue;
                    }
                    out.add(point);
                }
            }
        }
    }

    /** Per-thread working set for collect; not re-entrant (resolvers never call back into collect). */
    private static final class Scratch {
        final Vector3f localMin = new Vector3f();
        final Vector3f localMax = new Vector3f();
        final Vector3f cornerLocal = new Vector3f();
        final Vector3f local = new Vector3f();
        final Vector3f normal = new Vector3f();
        final float[] uSamples = new float[MAX_SAMPLES_PER_AXIS + 2];
        final float[] vSamples = new float[MAX_SAMPLES_PER_AXIS + 2];
        final OBB.SatFrame frame = new OBB.SatFrame();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * Per-hull plane frame + outline, cached: inside-test was a sqrt per edge per sample before
     * edge normals were precomputed here.
     */
    private static final class FaceFrames {

        /** Per thread, not shared: a hull belongs to a vehicle type, so entries are few; avoids a lock contended across physics workers. */
        private static final ThreadLocal<Map<ConvexHull, FaceFrames>> CACHE =
                ThreadLocal.withInitial(WeakHashMap::new);

        final float[] ux, uy, uz, vx, vy, vz;
        final float[] minU, maxU, minV, maxV;
        final VehicleCubeOBB.CubeFace[] bucket;
        /** Inward edge normals and offsets per face, laid out as a face's slice of one array. */
        final float[] edgeNX, edgeNY, edgeD;
        final int[] edgeStart;

        static FaceFrames of(ConvexHull hull) {
            return CACHE.get().computeIfAbsent(hull, FaceFrames::new);
        }

        private FaceFrames(ConvexHull hull) {
            int faces = hull.faceCount();
            ux = new float[faces];
            uy = new float[faces];
            uz = new float[faces];
            vx = new float[faces];
            vy = new float[faces];
            vz = new float[faces];
            minU = new float[faces];
            maxU = new float[faces];
            minV = new float[faces];
            maxV = new float[faces];
            bucket = new VehicleCubeOBB.CubeFace[faces];
            edgeStart = new int[faces + 1];

            float[] planes = hull.planes();
            float[] points = hull.points();
            int[] vertices = hull.faceVertices();
            int totalEdges = 0;
            for (int face = 0; face < faces; face++) {
                totalEdges += hull.faceEnd(face) - hull.faceStart(face);
            }
            edgeNX = new float[totalEdges];
            edgeNY = new float[totalEdges];
            edgeD = new float[totalEdges];

            Vector3f normal = new Vector3f();
            Vector3f u = new Vector3f();
            Vector3f v = new Vector3f();
            Vector3f origin = new Vector3f();
            int edge = 0;
            for (int face = 0; face < faces; face++) {
                normal.set(planes[face * 4], planes[face * 4 + 1], planes[face * 4 + 2]);
                planeFrame(normal, u, v);
                origin.set(normal).mul(planes[face * 4 + 3]);
                ux[face] = u.x; uy[face] = u.y; uz[face] = u.z;
                vx[face] = v.x; vy[face] = v.y; vz[face] = v.z;
                bucket[face] = VehicleCubeOBB.CubeFace.of(normal.x, normal.y, normal.z);

                int from = hull.faceStart(face);
                int to = hull.faceEnd(face);
                int corners = to - from;
                float[] flat = new float[corners * 2];
                float lowU = Float.MAX_VALUE, highU = -Float.MAX_VALUE;
                float lowV = Float.MAX_VALUE, highV = -Float.MAX_VALUE;
                for (int i = 0; i < corners; i++) {
                    int at = vertices[from + i] * 3;
                    float dx = points[at] - origin.x;
                    float dy = points[at + 1] - origin.y;
                    float dz = points[at + 2] - origin.z;
                    float pu = dx * u.x + dy * u.y + dz * u.z;
                    float pv = dx * v.x + dy * v.y + dz * v.z;
                    flat[i * 2] = pu;
                    flat[i * 2 + 1] = pv;
                    lowU = Math.min(lowU, pu);
                    highU = Math.max(highU, pu);
                    lowV = Math.min(lowV, pv);
                    highV = Math.max(highV, pv);
                }
                minU[face] = lowU;
                maxU[face] = highU;
                minV[face] = lowV;
                maxV[face] = highV;

                edgeStart[face] = edge;
                for (int i = 0; i < corners; i++) {
                    int j = (i + 1) % corners;
                    float ex = flat[j * 2] - flat[i * 2];
                    float ey = flat[j * 2 + 1] - flat[i * 2 + 1];
                    float length = (float) Math.sqrt(ex * ex + ey * ey);
                    if (length < 1.0e-6f) {
                        continue;
                    }
                    // Inward normal assumes ConvexHull's winding; inside = positive side of every edge.
                    float nx = -ey / length;
                    float ny = ex / length;
                    edgeNX[edge] = nx;
                    edgeNY[edge] = ny;
                    edgeD[edge] = nx * flat[i * 2] + ny * flat[i * 2 + 1];
                    edge++;
                }
            }
            edgeStart[faces] = edge;
        }

        /** Margin matches the box path's FACE_GAP: a contact just past an edge (hull on a block corner) survives. */
        boolean inside(int face, float u, float v) {
            for (int i = edgeStart[face], end = edgeStart[face + 1]; i < end; i++) {
                if (edgeNX[i] * u + edgeNY[i] * v - edgeD[i] < -FACE_GAP) {
                    return false;
                }
            }
            return true;
        }

    }

    /** Smallest projection of an axis-aligned span onto a direction. */
    private static float low(Vector3f min, Vector3f max, float x, float y, float z) {
        return (x >= 0 ? min.x : max.x) * x + (y >= 0 ? min.y : max.y) * y
                + (z >= 0 ? min.z : max.z) * z;
    }

    /** Largest projection of an axis-aligned span onto a direction. */
    private static float high(Vector3f min, Vector3f max, float x, float y, float z) {
        return (x >= 0 ? max.x : min.x) * x + (y >= 0 ? max.y : min.y) * y
                + (z >= 0 ? max.z : min.z) * z;
    }

    /** @return face of shallowest penetration, or -1 (box wholly beyond some face's plane: no contact) */
    private static int hullFace(ConvexHull hull, Vector3f localMin, Vector3f localMax) {
        float[] planes = hull.planes();
        int best = -1;
        float shallowest = Float.MAX_VALUE;
        for (int face = 0, faces = hull.faceCount(); face < faces; face++) {
            float nx = planes[face * 4], ny = planes[face * 4 + 1], nz = planes[face * 4 + 2];
            // Box's nearest corner along the outward normal (support point).
            float nearest = (nx >= 0 ? localMin.x : localMax.x) * nx
                    + (ny >= 0 ? localMin.y : localMax.y) * ny
                    + (nz >= 0 ? localMin.z : localMax.z) * nz;
            float penetration = planes[face * 4 + 3] - nearest;
            if (penetration < 0) {
                return -1;
            }
            if (penetration < shallowest) {
                shallowest = penetration;
                best = face;
            }
        }
        return best;
    }

    /** u = in-plane direction closest to world up => v has no vertical component; climb-skirt height depends on u alone. */
    private static void planeFrame(Vector3f normal, Vector3f u, Vector3f v) {
        if (Math.abs(normal.y) > 0.999f) {
            // Floor/roof: normal is up, no in-plane up to align with.
            u.set(1, 0, 0);
        } else {
            u.set(0, 1, 0).sub(normal.x * normal.y, normal.y * normal.y, normal.z * normal.y);
            u.normalize();
        }
        normal.cross(u, v);
        v.normalize();
    }

    /** Half-open on the far face, like a block cell: a point exactly at max belongs to the next cell. */
    private static boolean contains(VehicleCubeOBB.CubePoint point,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ) {
        return point.worldX() >= minX && point.worldX() < maxX
                && point.worldY() >= minY && point.worldY() < maxY
                && point.worldZ() >= minZ && point.worldZ() < maxZ;
    }

    /**
     * Indexed by axis * 2 + (negative ? 1 : 0), with faces ordered as LEFT, RIGHT, TOP, BOTTOM, FRONT, BACK.
     */
    private static final VehicleCubeOBB.CubeFace[] FACES = {
            VehicleCubeOBB.CubeFace.LEFT, VehicleCubeOBB.CubeFace.RIGHT,
            VehicleCubeOBB.CubeFace.TOP, VehicleCubeOBB.CubeFace.BOTTOM,
            VehicleCubeOBB.CubeFace.FRONT, VehicleCubeOBB.CubeFace.BACK
    };

    /**
     * Hull's own 3 axes only (not full SAT): handles zero-overlap contact, e.g. resting with no penetration.
     * @return axis * 2 + (negative ? 1 : 0)
     */
    private static int contactFace(OBB obb, AABB box, Vector3f[] axes) {
        Vector3f extents = obb.extents();
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        // Double centre: the float mirror is off by up to half a block at 1e7.
        double offsetX = box.minX + halfX - obb.centerX();
        double offsetY = box.minY + halfY - obb.centerY();
        double offsetZ = box.minZ + halfZ - obb.centerZ();

        int bestAxis = 1;
        double bestOverlap = Double.MAX_VALUE;
        double bestDistance = -1;
        for (int axis = 0; axis < 3; axis++) {
            Vector3f direction = axes[axis];
            double boxRadius = Math.abs(direction.x) * halfX
                    + Math.abs(direction.y) * halfY
                    + Math.abs(direction.z) * halfZ;
            double distance = offsetX * direction.x + offsetY * direction.y + offsetZ * direction.z;
            double overlap = boxRadius + extents.get(axis) - Math.abs(distance);
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestAxis = axis;
                bestDistance = distance;
            }
        }
        return bestAxis * 2 + (bestDistance < 0 ? 1 : 0);
    }

    /**
     * Writes the two extremes (support polygon needs these exactly, unlike fixed even spacing,
     * which undershot a 16-block merged box's real extent), interior rows for a wide span, and
     * {@code extra} if inside [from, to].
     * @return entries written
     */
    private static int axisSamples(float from, float to, float extra, float[] out) {
        float span = to - from;
        int count = 0;
        if (span <= 2 * EDGE_INSET) {
            out[count++] = (from + to) * 0.5f;
        } else {
            float lo = from + EDGE_INSET;
            float hi = to - EDGE_INSET;
            out[count++] = lo;
            int interior = Mth.clamp(Mth.floor(span) - 1, 0, MAX_INTERIOR_SAMPLES);
            for (int i = 1; i <= interior; i++) {
                out[count++] = lo + (hi - lo) * i / (interior + 1);
            }
            out[count++] = hi;
        }
        if (extra >= from && extra <= to) {
            out[count++] = extra;
        }
        return count;
    }

}
