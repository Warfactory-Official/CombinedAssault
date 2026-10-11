package com.wf.combinedassault.vehicle.collision;

import com.wf.wflib.util.OBB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;

/** One hull's collision pieces as flat arrays: unit axes, extents, world AABB half sizes, pose. Reused; no allocation. */
public final class HullPieces {

    /**
     * Body-pair cache: 5 mm, cos(0.1 deg / 2) (3-block half diagonal: 5 mm). Jolt 1 mm FORBIDDEN: settling hulls drift
     * 0.5-2 mm/tick => 2-14% hits ({@code PitBench}). Jolt 2 deg: 10 cm at a hull end.
     */
    private static final double SAME_POSITION_SQ = 2.5e-5;
    private static final float SAME_ROTATION = 0.99999962f;

    private int count;
    private double[] centre = new double[24];
    /** Piece i axis k: [9i + 3k, +3). */
    private float[] axes = new float[72];
    private float[] extents = new float[24];
    private float[] reach = new float[24];
    private float[] rotation = new float[32];
    private final Vector3f axis = new Vector3f();
    private static final Quaternionf IDENTITY = new Quaternionf();

    public void load(List<OBB> pieces) {
        count = 0;
        for (int i = 0, size = pieces.size(); i < size; i++) {
            add(pieces.get(i));
        }
    }

    public void load(OBB piece) {
        count = 0;
        add(piece);
    }

    public void clear() {
        count = 0;
    }

    /** Axis-aligned piece, e.g. a block box. */
    public void addBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        add((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5, IDENTITY,
                (float) ((maxX - minX) * 0.5), (float) ((maxY - minY) * 0.5), (float) ((maxZ - minZ) * 0.5));
    }

    private void add(OBB piece) {
        Vector3f e = piece.extents();
        add(piece.centerX(), piece.centerY(), piece.centerZ(), piece.rotation(), e.x, e.y, e.z);
    }

    private void add(double cx, double cy, double cz, Quaternionf q, float ex, float ey, float ez) {
        int i = count++;
        if (centre.length < count * 3) {
            int capacity = count * 2;
            centre = Arrays.copyOf(centre, capacity * 3);
            axes = Arrays.copyOf(axes, capacity * 9);
            extents = Arrays.copyOf(extents, capacity * 3);
            reach = Arrays.copyOf(reach, capacity * 3);
            rotation = Arrays.copyOf(rotation, capacity * 4);
        }
        centre[3 * i] = cx;
        centre[3 * i + 1] = cy;
        centre[3 * i + 2] = cz;
        rotation[4 * i] = q.x;
        rotation[4 * i + 1] = q.y;
        rotation[4 * i + 2] = q.z;
        rotation[4 * i + 3] = q.w;
        extents[3 * i] = ex;
        extents[3 * i + 1] = ey;
        extents[3 * i + 2] = ez;
        float rx = 0, ry = 0, rz = 0;
        for (int k = 0; k < 3; k++) {
            q.transform(k == 0 ? 1 : 0, k == 1 ? 1 : 0, k == 2 ? 1 : 0, axis).normalize();
            int at = 9 * i + 3 * k;
            axes[at] = axis.x;
            axes[at + 1] = axis.y;
            axes[at + 2] = axis.z;
            float extent = extents[3 * i + k];
            rx += Math.abs(axis.x) * extent;
            ry += Math.abs(axis.y) * extent;
            rz += Math.abs(axis.z) * extent;
        }
        reach[3 * i] = rx;
        reach[3 * i + 1] = ry;
        reach[3 * i + 2] = rz;
    }

    public int count() {
        return count;
    }

    public double centre(int i, int component) {
        return centre[3 * i + component];
    }

    public float axis(int i, int k, int component) {
        return axes[9 * i + 3 * k + component];
    }

    public float extent(int i, int k) {
        return extents[3 * i + k];
    }

    /** Piece i swept from offset (0) to (sx, sy, sz) vs other's piece j: world AABBs meet. */
    public boolean boundsMeet(int i, HullPieces other, int j, double sx, double sy, double sz) {
        for (int c = 0; c < 3; c++) {
            double sweep = c == 0 ? sx : c == 1 ? sy : sz;
            double low = centre[3 * i + c] - reach[3 * i + c] + Math.min(0, sweep);
            double high = centre[3 * i + c] + reach[3 * i + c] + Math.max(0, sweep);
            double otherCentre = other.centre[3 * j + c], otherReach = other.reach[3 * j + c];
            if (high < otherCentre - otherReach || low > otherCentre + otherReach) {
                return false;
            }
        }
        return true;
    }

    /** = {@code OBB.mtv(piece i moved by d, other piece j) > 0}. */
    public boolean overlaps(int i, HullPieces other, int j, double dx, double dy, double dz) {
        float tx = (float) (other.centre[3 * j] - centre[3 * i] - dx);
        float ty = (float) (other.centre[3 * j + 1] - centre[3 * i + 1] - dy);
        float tz = (float) (other.centre[3 * j + 2] - centre[3 * i + 2] - dz);
        float[] a = axes, b = other.axes;
        int ai = 9 * i, bj = 9 * j;
        for (int k = 0; k < 6; k++) {
            float[] source = k < 3 ? a : b;
            int at = (k < 3 ? ai : bj) + 3 * (k % 3);
            if (!separatesNot(source[at], source[at + 1], source[at + 2], tx, ty, tz, i, other, j)) {
                return false;
            }
        }
        for (int k = 0; k < 3; k++) {
            int ak = ai + 3 * k;
            for (int m = 0; m < 3; m++) {
                int bm = bj + 3 * m;
                float x = a[ak + 1] * b[bm + 2] - a[ak + 2] * b[bm + 1];
                float y = a[ak + 2] * b[bm] - a[ak] * b[bm + 2];
                float z = a[ak] * b[bm + 1] - a[ak + 1] * b[bm];
                float length = (float) Math.sqrt(x * x + y * y + z * z);
                if (length < 1.0e-4f) {
                    continue;
                }
                if (!separatesNot(x / length, y / length, z / length, tx, ty, tz, i, other, j)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Overlap along (x, y, z) > 0. */
    private boolean separatesNot(float x, float y, float z, float tx, float ty, float tz, int i, HullPieces other, int j) {
        return radius(x, y, z, i) + other.radius(x, y, z, j) - Math.abs(x * tx + y * ty + z * tz) > 0;
    }

    private float radius(float x, float y, float z, int i) {
        int at = 9 * i;
        return Math.abs(x * axes[at] + y * axes[at + 1] + z * axes[at + 2]) * extents[3 * i]
                + Math.abs(x * axes[at + 3] + y * axes[at + 4] + z * axes[at + 5]) * extents[3 * i + 1]
                + Math.abs(x * axes[at + 6] + y * axes[at + 7] + z * axes[at + 8]) * extents[3 * i + 2];
    }

    public void copyFrom(HullPieces source) {
        count = source.count;
        if (centre.length < source.centre.length) {
            centre = new double[source.centre.length];
            axes = new float[source.axes.length];
            extents = new float[source.extents.length];
            reach = new float[source.reach.length];
            rotation = new float[source.rotation.length];
        }
        System.arraycopy(source.centre, 0, centre, 0, 3 * count);
        System.arraycopy(source.axes, 0, axes, 0, 9 * count);
        System.arraycopy(source.extents, 0, extents, 0, 3 * count);
        System.arraycopy(source.reach, 0, reach, 0, 3 * count);
        System.arraycopy(source.rotation, 0, rotation, 0, 4 * count);
    }

    /** Every piece within the cache tolerance of other's, same extents. */
    public boolean samePose(HullPieces other) {
        if (count != other.count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            double dx = centre[3 * i] - other.centre[3 * i];
            double dy = centre[3 * i + 1] - other.centre[3 * i + 1];
            double dz = centre[3 * i + 2] - other.centre[3 * i + 2];
            if (dx * dx + dy * dy + dz * dz > SAME_POSITION_SQ) {
                return false;
            }
            float dot = rotation[4 * i] * other.rotation[4 * i] + rotation[4 * i + 1] * other.rotation[4 * i + 1]
                    + rotation[4 * i + 2] * other.rotation[4 * i + 2] + rotation[4 * i + 3] * other.rotation[4 * i + 3];
            if (Math.abs(dot) < SAME_ROTATION || extents[3 * i] != other.extents[3 * i]
                    || extents[3 * i + 1] != other.extents[3 * i + 1] || extents[3 * i + 2] != other.extents[3 * i + 2]) {
                return false;
            }
        }
        return true;
    }
}
