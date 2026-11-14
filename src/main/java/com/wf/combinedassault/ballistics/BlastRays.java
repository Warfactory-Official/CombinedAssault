package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One ray pass per blast per vehicle: {@link #RAYS} Fibonacci rays from the centre, culled to the vehicle's bounding
 * sphere, each through {@link AddonStrike.Panels} to the hull box. Panels and plates read the same pass: exposure
 * (panel stage) and <= {@link #SAMPLES} evenly spaced hull-bound rays ({@link Pass#count}) for plate resolution.
 */
public final class BlastRays {

    public static final int RAYS = 1024;
    public static final int SAMPLES = 64;
    /** Bounding box -> panels: slat standoff past the hull box. */
    private static final double PANEL_MARGIN = 1.5;
    private static final float[] DIRS = fibonacci(RAYS);

    private static final ThreadLocal<int[]> HIT_RAY = ThreadLocal.withInitial(() -> new int[RAYS]);
    private static final ThreadLocal<float[]> HIT_POWER = ThreadLocal.withInitial(() -> new float[RAYS]);
    private static final ThreadLocal<float[]> HIT_DISTANCE = ThreadLocal.withInitial(() -> new float[RAYS]);

    private BlastRays() {
    }

    /**
     * @param exposure   hull-open share of the blast after panels (falloff-weighted); 1 = none installed or no ray met
     *                   the hull
     * @param hullRays   rays that met the hull
     * @param count      samples
     * @param direction  per sample, unit, 3 floats
     * @param distance   per sample: centre to hull box, m
     * @param power      per sample: share left after panels
     * @param reach      blast reach, m
     */
    public static final class Pass {
        public final float reach;
        public float exposure = 1;
        public int hullRays;
        public int count;
        public final float[] direction = new float[3 * SAMPLES];
        public final float[] distance = new float[SAMPLES];
        public final float[] power = new float[SAMPLES];

        private Pass(float reach) {
            this.reach = reach;
        }

        public Vec3 dir(int i) {
            return new Vec3(direction[3 * i], direction[3 * i + 1], direction[3 * i + 2]);
        }
    }

    /** Fluence weight of a hull point at {@code fraction} of the reach. */
    public static float falloff(double fraction) {
        float f = (float) Math.max(0, 1 - fraction);
        return f * f;
    }

    /** Unit direction {@code i} of {@link #RAYS}. */
    public static Vec3 dir(int i) {
        return new Vec3(DIRS[3 * i], DIRS[3 * i + 1], DIRS[3 * i + 2]);
    }

    /** @param shape running AEF blast; null = vanilla sphere */
    public static Pass cast(AbstractVehicle vehicle, Vec3 centre, float size, @Nullable ExplosionAEF shape) {
        float reach = size * 2.0f;
        Pass pass = new Pass(reach);
        if (size <= 0) {
            return pass;
        }
        AABB box = vehicle.getBoundingBox().inflate(PANEL_MARGIN);
        Vec3 toBox = box.getCenter().subtract(centre);
        double distance = toBox.length();
        double bound = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                + box.getZsize() * box.getZsize());
        if (distance - bound > reach) {
            return pass;
        }
        boolean inside = box.contains(centre);
        double cosCone = distance <= bound ? -1 : Math.cos(Math.asin(bound / distance));
        Vec3 axis = distance < 1.0e-6 ? Vec3.ZERO : toBox.scale(1 / distance);
        AddonStrike.Panels panels = AddonStrike.panels(vehicle, size, shape == null ? null : shape.payload().fragments());
        List<OBB> hullBoxes = vehicle.getOBBs();
        int[] hitRay = HIT_RAY.get();
        float[] hitPower = HIT_POWER.get();
        float[] hitDistance = HIT_DISTANCE.get();
        int hits = 0;
        double hullAll = 0;
        double hullOpen = 0;
        for (int r = 0; r < RAYS; r++) {
            float dx = DIRS[3 * r];
            float dy = DIRS[3 * r + 1];
            float dz = DIRS[3 * r + 2];
            if (dx * axis.x + dy * axis.y + dz * axis.z < cosCone) {
                continue;
            }
            Vec3 end = centre.add(dx * reach, dy * reach, dz * reach);
            if (!inside && box.clip(centre, end).isEmpty()
                    || shape != null && !shape.inBlastShape(centre.add(dx, dy, dz))) {
                continue;
            }
            OBB.SegmentHit hull = OBB.first(hullBoxes, centre, end);
            double hullFraction = hull == null ? 1.0 : (hull.distance() + AddonArmorLayer.SKIN_INSET) / reach;
            float power = panels == null ? 1 : panels.ray(centre, end, dx, dy, dz, hullFraction);
            if (hull != null) {
                float falloff = falloff(hull.distance() / reach);
                hullAll += falloff;
                hullOpen += power * falloff;
                hitRay[hits] = r;
                hitPower[hits] = power;
                hitDistance[hits] = (float) hull.distance();
                hits++;
            }
        }
        if (panels != null) {
            panels.finish();
        }
        pass.exposure = hullAll <= 0 ? 1 : (float) (hullOpen / hullAll);
        pass.hullRays = hits;
        int count = Math.min(hits, SAMPLES);
        for (int j = 0; j < count; j++) {
            int k = (int) ((j + 0.5) * hits / count);
            int r = hitRay[k];
            pass.direction[3 * j] = DIRS[3 * r];
            pass.direction[3 * j + 1] = DIRS[3 * r + 1];
            pass.direction[3 * j + 2] = DIRS[3 * r + 2];
            pass.distance[j] = hitDistance[k];
            pass.power[j] = hitPower[k];
        }
        pass.count = count;
        return pass;
    }

    private static float[] fibonacci(int n) {
        float[] dirs = new float[3 * n];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) * 2.0 / n;
            double ring = Math.sqrt(1 - y * y);
            double phi = golden * i;
            dirs[3 * i] = (float) (Math.cos(phi) * ring);
            dirs[3 * i + 1] = (float) y;
            dirs[3 * i + 2] = (float) (Math.sin(phi) * ring);
        }
        return dirs;
    }
}
