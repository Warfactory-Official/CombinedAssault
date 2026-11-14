package com.wf.combinedassault.ballistics.hit;

import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.PlateLayer;
import com.wf.wflib.armor.api.hull.PlateSpec;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Load-time join of the mesh's {@code armor:<id>} faces and {@code armor.plates}: ids must match both ways; a plate
 * without {@code facing} that is not {@code curved} takes the area-weighted normal of its faces (model frame, parts at
 * rest) as {@code facing} + {@code slope}; then every plate compiles.
 */
public final class PlateBinding {

    /** Face normals this unanimous (|sum| / area) or the fit is refused. */
    private static final float COHERENCE = 0.5f;
    /** Below this horizontal share the fitted normal is top / bottom. */
    private static final float HORIZONTAL_EPS = 1.0e-3f;

    private PlateBinding() {
    }

    /** @throws IllegalStateException naming the plate on any mismatch or invalid declaration */
    public static void bind(@Nullable HitMesh mesh, @Nullable HullProfile armor) {
        Map<String, PlateSpec> declared = armor == null ? Map.of() : armor.plates;
        if (mesh == null) {
            if (!declared.isEmpty()) {
                throw new IllegalStateException("armor.plates " + new TreeSet<>(declared.keySet())
                        + " but the vehicle has no hit mesh");
            }
            return;
        }
        double[][] sums = new double[mesh.plates().size()][4];
        for (HitMesh.Node node : mesh.nodes()) {
            if (node.mesh() != null) {
                accumulate(node.mesh(), node.rotation(), sums);
            }
        }
        Set<String> bound = new HashSet<>();
        for (int i = 0; i < sums.length; i++) {
            if (sums[i][3] > 0) {
                bound.add(mesh.plates().get(i));
            }
        }
        Set<String> missing = new TreeSet<>(bound);
        missing.removeAll(declared.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(mesh.source() + ": armor:<id> materials " + missing
                    + " have no entry in armor.plates");
        }
        Set<String> unbound = new TreeSet<>(declared.keySet());
        unbound.removeAll(bound);
        if (!unbound.isEmpty()) {
            throw new IllegalStateException("armor.plates " + unbound + " bound by no armor:<id> face of "
                    + mesh.source());
        }
        for (int i = 0; i < sums.length; i++) {
            if (sums[i][3] > 0) {
                String id = mesh.plates().get(i);
                PlateSpec spec = declared.get(id);
                if (spec.facing == null && !spec.curved && spec.slope == null) {
                    fit(id, spec, sums[i]);
                }
            }
        }
        if (armor != null) {
            armor.validate();
        }
    }

    /** {@code sums[plate]} += area-weighted normal xyz, area (when positive: plate has faces). */
    private static void accumulate(TriangleBvh bvh, Quaternionf rotation, double[][] sums) {
        float[] t = bvh.triangles();
        Vector3f n = new Vector3f();
        for (int i = 0; i < bvh.triangleCount(); i++) {
            int plate = bvh.plate(i);
            if (plate == 0) {
                continue;
            }
            int a = i * 9;
            float e1x = t[a + 3] - t[a], e1y = t[a + 4] - t[a + 1], e1z = t[a + 5] - t[a + 2];
            float e2x = t[a + 6] - t[a], e2y = t[a + 7] - t[a + 1], e2z = t[a + 8] - t[a + 2];
            rotation.transform(n.set(e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x));
            double[] sum = sums[plate - 1];
            sum[0] += n.x * 0.5;
            sum[1] += n.y * 0.5;
            sum[2] += n.z * 0.5;
            sum[3] += n.length() * 0.5;
        }
    }

    private static void fit(String id, PlateSpec spec, double[] sum) {
        double length = Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
        if (length < COHERENCE * sum[3]) {
            throw new IllegalStateException("plate '" + id + "': faces point every way (|sum| / area "
                    + String.format(Locale.ROOT, "%.2f", length / sum[3]) + "); declare facing or curved");
        }
        float x = (float) (sum[0] / length), y = (float) (sum[1] / length), z = (float) (sum[2] / length);
        if (Math.hypot(x, z) < HORIZONTAL_EPS) {
            spec.facing = y > 0 ? "top" : "bottom";
            return;
        }
        if (y < 0) {
            throw new IllegalStateException("plate '" + id + "': fitted normal faces downward; declare facing or curved");
        }
        double azimuth = Math.toDegrees(Math.atan2(-x, z));
        spec.facing = String.format(Locale.ROOT, "%.1f", azimuth < 0 ? azimuth + 360 : azimuth);
        spec.slope = (float) Math.toDegrees(Math.asin(Math.min(1, y)));
    }

    /** {@code facing slope, layers} or LOS figures, for probes and F3. */
    public static String describe(PlateSpec spec) {
        String normal = spec.curved ? "curved" : (spec.facing == null ? "?" : spec.facing)
                + (spec.slope == null ? "" : String.format(Locale.ROOT, " %.0f deg", spec.slope));
        if (spec.los != null) {
            return String.format(Locale.ROOT, "%s, LOS KE %.0f / CE %.0f", normal, spec.los.ke(), spec.los.ce());
        }
        StringBuilder layers = new StringBuilder();
        for (PlateLayer layer : spec.layers) {
            layers.append(layers.length() == 0 ? "" : " + ").append(layer.isGap()
                    ? String.format(Locale.ROOT, "gap %.2f", layer.gap()) : String.format(Locale.ROOT, "%.0f %s",
                    layer.mm(), layer.mat()));
        }
        return normal + ", " + layers;
    }
}
