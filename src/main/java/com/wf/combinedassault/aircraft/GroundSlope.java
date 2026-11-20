package com.wf.combinedassault.aircraft;

import com.wf.combinedassault.dynamics.part.LandingGearUnit;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Terrain under a grounded airframe: least-squares plane through the collision tops under the gear feet, else under
 * the main box's four footprint corners. Sample over no ground (ledge, pit) dropped: tail wheel past a pad edge
 * pitched an Mi-28 6.9 deg into the void. Fewer than 3 left => level.
 *
 * @param pitch  deg, entity xRot sign
 * @param roll   deg, entity zRot sign
 * @param gap    lowest gear foot above its ground, blocks; NaN: no foot over ground
 * @param footed every gear foot over ground
 */
public record GroundSlope(float pitch, float roll, double gap, boolean footed) {

    /** Main box footprint share sampled without gear feet. */
    private static final float SPAN = 0.8f;
    /** Blocks above / below the sample searched for ground. */
    private static final int REACH = 2;
    private static final float MAX = 30;

    public static GroundSlope under(AbstractVehicle vehicle) {
        OBB hull = vehicle.getMainCubeOBB().obb();
        Level level = vehicle.level();
        Quaternionf heading = new Quaternionf().rotateY((float) Math.toRadians(-vehicle.getYRot()));
        Vector3f at = new Vector3f();
        List<Vector3f> samples = new ArrayList<>();
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (unit instanceof LandingGearUnit gear && !gear.isDetached()) {
                for (int i = 0; i < gear.footCount(); i++) {
                    samples.add(new Vector3f(gear.foot(i)));
                }
            }
        }
        double gap = Double.NaN;
        boolean footed = !samples.isEmpty();
        double[] top = new double[Math.max(4, samples.size())];
        for (int i = 0; i < samples.size(); i++) {
            Vector3f sample = samples.get(i);
            heading.transform(at.set(sample.x, 0, sample.z));
            top[i] = top(level, hull.centerX() + at.x, hull.centerY() + sample.y, hull.centerZ() + at.z);
            if (Double.isNaN(top[i])) {
                footed = false;
            } else {
                double foot = hull.centerY() + hull.rotation().transform(at.set(sample)).y - top[i];
                gap = Double.isNaN(gap) ? foot : Math.min(gap, foot);
            }
        }
        if (samples.size() < 3) {
            samples.clear();
            Vector3f extents = hull.extents();
            for (int i = 0; i < 4; i++) {
                Vector3f sample = new Vector3f((i & 1) == 0 ? -extents.x : extents.x, -extents.y, i < 2 ? -extents.z : extents.z)
                        .mul(SPAN, 1, SPAN);
                samples.add(sample);
                heading.transform(at.set(sample.x, 0, sample.z));
                top[i] = top(level, hull.centerX() + at.x, hull.centerY() + sample.y, hull.centerZ() + at.z);
            }
        }
        int n = 0;
        for (int i = 0; i < samples.size(); i++) {
            if (!Double.isNaN(top[i])) {
                samples.set(n, samples.get(i));
                top[n++] = top[i];
            }
        }
        if (n < 3) {
            return new GroundSlope(0, 0, gap, footed);
        }
        double meanX = 0, meanZ = 0, meanTop = 0;
        for (int i = 0; i < n; i++) {
            meanX += samples.get(i).x / n;
            meanZ += samples.get(i).z / n;
            meanTop += top[i] / n;
        }
        double xx = 0, zz = 0, xz = 0, xt = 0, zt = 0;
        for (int i = 0; i < n; i++) {
            double x = samples.get(i).x - meanX, z = samples.get(i).z - meanZ, t = top[i] - meanTop;
            xx += x * x;
            zz += z * z;
            xz += x * z;
            xt += x * t;
            zt += z * t;
        }
        double det = xx * zz - xz * xz;
        if (Math.abs(det) < 1.0e-6) {
            return new GroundSlope(0, 0, gap, footed);
        }
        double pitch = -Math.atan((zt * xx - xt * xz) / det);
        double roll = Math.atan((xt * zz - zt * xz) / det);
        return new GroundSlope(Mth.clamp((float) Math.toDegrees(pitch), -MAX, MAX),
                Mth.clamp((float) Math.toDegrees(roll), -MAX, MAX), gap, footed);
    }

    /** Highest collision top in the column within {@link #REACH} of {@code y}; NaN: none. */
    private static double top(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int cy = Mth.floor(y) + REACH; cy >= Mth.floor(y) - REACH; cy--) {
            VoxelShape shape = level.getBlockState(cell.set(Mth.floor(x), cy, Mth.floor(z))).getCollisionShape(level, cell);
            if (!shape.isEmpty()) {
                return cy + shape.max(Direction.Axis.Y);
            }
        }
        return Double.NaN;
    }
}
