package com.wf.combinedassault.uav.multirotor;

import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rotor {@code unit} pools of a multirotor as thrust points: body-frame position about their centroid, spin (+1 cw).
 * {@link #balanced}: max uniform-share thrust with zero roll / pitch / yaw torque, {@code c_i <= e_i}; LP by vertex
 * enumeration (3 equality rows), cached per effectiveness vector.
 */
public final class RotorLayout {
    /** Prop yaw reaction / thrust, m. */
    private static final double YAW_ARM = 0.02;
    private static final double EPS = 1.0E-6;

    private final int[] pools;
    private final double[] x;
    private final double[] z;
    private final int[] spin;
    /** Mean arm length, m. */
    private final double arm;
    private final double[] effectiveness;
    private double[] cachedFor = new double[0];
    private double balanced;

    private RotorLayout(int[] pools, double[] x, double[] z, int[] spin) {
        this.pools = pools;
        this.x = x;
        this.z = z;
        this.spin = spin;
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            sum += Math.hypot(x[i], z[i]);
        }
        this.arm = sum / x.length;
        this.effectiveness = new double[pools.length];
    }

    /** Null: hits not loaded yet, or fewer than 2 rotor units (lumped VehicleRotors applies). */
    @Nullable
    public static RotorLayout of(AbstractVehicle vehicle) {
        VehicleHits hits = vehicle.hits();
        HullComponents systems = vehicle.hullComponents();
        if (hits == null) {
            return null;
        }
        Map<String, List<HitModel.Node>> units = new LinkedHashMap<>();
        for (HitModel.Node node : hits.model().nodes()) {
            if (node.kind() == ComponentKind.ROTOR && !node.unit().isEmpty()) {
                units.computeIfAbsent(node.systemId(), k -> new ArrayList<>()).add(node);
            }
        }
        if (units.size() < 2) {
            return null;
        }
        int n = units.size();
        int[] pools = new int[n];
        double[] x = new double[n];
        double[] z = new double[n];
        int[] spin = new int[n];
        int i = 0;
        double cx = 0;
        double cz = 0;
        for (Map.Entry<String, List<HitModel.Node>> e : units.entrySet()) {
            pools[i] = systems.indexOf(e.getKey());
            Vector3f sum = new Vector3f();
            for (HitModel.Node node : e.getValue()) {
                sum.add(node.mesh().origin());
                if (node.spin() != 0) {
                    spin[i] = node.spin();
                }
            }
            if (pools[i] < 0 || spin[i] == 0) {
                throw new IllegalStateException(vehicle.getVehicleId() + ": rotor unit " + e.getKey()
                        + (pools[i] < 0 ? " has no pool" : " declares no spin (cw / ccw)"));
            }
            x[i] = sum.x / e.getValue().size();
            z[i] = sum.z / e.getValue().size();
            cx += x[i];
            cz += z[i];
            i++;
        }
        for (int k = 0; k < n; k++) {
            x[k] -= cx / n;
            z[k] -= cz / n;
        }
        return new RotorLayout(pools, x, z, spin);
    }

    public int size() {
        return pools.length;
    }

    /** Refresh effectiveness from the pools; call once per tick before {@link #balanced} / {@link #torque}. */
    public void update(HullComponents systems) {
        for (int i = 0; i < pools.length; i++) {
            effectiveness[i] = systems.effectiveness(pools[i]);
        }
    }

    /** Share of full thrust holdable with zero torque, 0..1; 0 = no balanced hover (quad with a dead rotor). */
    public double balanced() {
        if (!Arrays.equals(effectiveness, cachedFor)) {
            cachedFor = effectiveness.clone();
            balanced = solve(effectiveness, x, z, spin) / pools.length;
        }
        return balanced;
    }

    /** Mean output share at {@code throttle} with nothing held back. */
    public double meanOutput(double throttle) {
        double sum = 0;
        for (double e : effectiveness) {
            sum += e;
        }
        return throttle * sum / pools.length;
    }

    /**
     * Body-frame angular acceleration, rad/s^2, each rotor at {@code throttle x e_i} of {@code thrust / n}:
     * {@code sum r_i x F_i} (+ yaw reaction) / I, {@code I = mass arm^2}.
     */
    public Vector3f torque(double throttle, double thrust, double mass) {
        double per = thrust / pools.length;
        double tx = 0;
        double ty = 0;
        double tz = 0;
        for (int i = 0; i < pools.length; i++) {
            double f = per * throttle * effectiveness[i];
            // r x (0, f, 0) = (-z f, 0, x f)
            tx += -z[i] * f;
            tz += x[i] * f;
            ty += spin[i] * f * YAW_ARM;
        }
        double inertia = mass * arm * arm;
        return new Vector3f((float) (tx / inertia), (float) (ty / inertia), (float) (tz / inertia));
    }

    /**
     * max sum c, 0 <= c_i <= e_i, A c = 0 (rows x, z, spin). Vertex: <= 3 basic variables, rest at a bound.
     */
    static double solve(double[] e, double[] x, double[] z, int[] spin) {
        int n = e.length;
        double[][] a = new double[3][n];
        for (int i = 0; i < n; i++) {
            a[0][i] = x[i];
            a[1][i] = z[i];
            a[2][i] = spin[i];
        }
        double best = 0;
        int[] basis = new int[3];
        for (int k = 0; k <= Math.min(3, n); k++) {
            best = Math.max(best, subsets(a, e, basis, 0, 0, k, best));
        }
        return best;
    }

    private static double subsets(double[][] a, double[] e, int[] basis, int from, int depth, int k, double best) {
        if (depth == k) {
            return Math.max(best, bounds(a, e, basis, k));
        }
        for (int i = from; i < e.length; i++) {
            basis[depth] = i;
            best = subsets(a, e, basis, i + 1, depth + 1, k, best);
        }
        return best;
    }

    /** Non-basic variables at 0 or e_i (every pattern); basic solved by least squares, kept if exact + in bounds. */
    private static double bounds(double[][] a, double[] e, int[] basis, int k) {
        int n = e.length;
        int[] free = new int[n - k];
        int f = 0;
        for (int i = 0; i < n; i++) {
            boolean basic = false;
            for (int b = 0; b < k; b++) {
                basic |= basis[b] == i;
            }
            if (!basic) {
                free[f++] = i;
            }
        }
        double best = 0;
        double[] c = new double[n];
        for (int mask = 0; mask < 1 << free.length; mask++) {
            Arrays.fill(c, 0);
            for (int j = 0; j < free.length; j++) {
                c[free[j]] = (mask >> j & 1) == 1 ? e[free[j]] : 0;
            }
            double[] rhs = new double[3];
            for (int r = 0; r < 3; r++) {
                for (int i = 0; i < n; i++) {
                    rhs[r] -= a[r][i] * c[i];
                }
            }
            double[] sol = leastSquares(a, basis, k, rhs);
            if (sol == null) {
                continue;
            }
            boolean ok = true;
            for (int b = 0; b < k && ok; b++) {
                ok = sol[b] >= -EPS && sol[b] <= e[basis[b]] + EPS;
                c[basis[b]] = sol[b];
            }
            for (int r = 0; r < 3 && ok; r++) {
                double res = 0;
                for (int i = 0; i < n; i++) {
                    res += a[r][i] * c[i];
                }
                ok = Math.abs(res) < 1.0E-5;
            }
            if (ok) {
                double sum = 0;
                for (double v : c) {
                    sum += v;
                }
                best = Math.max(best, sum);
            }
        }
        return best;
    }

    /** Normal equations on the k basic columns; null if singular. */
    @Nullable
    private static double[] leastSquares(double[][] a, int[] basis, int k, double[] rhs) {
        if (k == 0) {
            return new double[0];
        }
        double[][] m = new double[k][k + 1];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                for (int r = 0; r < 3; r++) {
                    m[i][j] += a[r][basis[i]] * a[r][basis[j]];
                }
            }
            for (int r = 0; r < 3; r++) {
                m[i][k] += a[r][basis[i]] * rhs[r];
            }
        }
        for (int col = 0; col < k; col++) {
            int pivot = col;
            for (int row = col + 1; row < k; row++) {
                if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(m[pivot][col]) < 1.0E-9) {
                return null;
            }
            double[] t = m[col];
            m[col] = m[pivot];
            m[pivot] = t;
            for (int row = 0; row < k; row++) {
                if (row != col) {
                    double factor = m[row][col] / m[col][col];
                    for (int j = col; j <= k; j++) {
                        m[row][j] -= factor * m[col][j];
                    }
                }
            }
        }
        double[] out = new double[k];
        for (int i = 0; i < k; i++) {
            out[i] = m[i][k] / m[i][i];
        }
        return out;
    }
}
