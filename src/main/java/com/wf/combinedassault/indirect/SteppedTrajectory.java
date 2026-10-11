package com.wf.combinedassault.indirect;

import net.minecraft.world.phys.Vec3;

/**
 * {@link Trajectory} of a quadratic-drag round: recurrence tabulated lazily up to {@code lifeTicks}, not solved.
 * Between ticks the round moves in a straight line => position interpolated linearly; velocity = the step's.
 * Beyond life => unreached (NaN).
 */
final class SteppedTrajectory extends Trajectory {

    private final RoundSpec spec;
    /** Index k = after k steps; valid {@code [0, filled]}. */
    private final double[] x;
    private final double[] y;
    private final double[] vx;
    private final double[] vy;
    private int filled;

    SteppedTrajectory(RoundSpec spec, double horizontalSpeed, double verticalSpeed) {
        super(horizontalSpeed, verticalSpeed, spec.drag(), spec.gravity());
        this.spec = spec;
        int n = Math.max(spec.lifeTicks(), 1) + 1;
        x = new double[n];
        y = new double[n];
        vx = new double[n];
        vy = new double[n];
        vx[0] = horizontalSpeed;
        vy[0] = verticalSpeed;
    }

    /** {@code [position, velocity]} after {@code ticks}; 3D, so speed includes the cross axis. */
    static Vec3[] step(Vec3 origin, Vec3 velocity, RoundSpec spec, double ticks) {
        double px = origin.x;
        double py = origin.y;
        double pz = origin.z;
        double ux = velocity.x;
        double uy = velocity.y;
        double uz = velocity.z;
        int whole = (int) Math.floor(ticks);
        for (int k = 0; k < whole; k++) {
            px += ux;
            py += uy;
            pz += uz;
            double decay = spec.decay(Math.sqrt(ux * ux + uy * uy + uz * uz));
            ux *= decay;
            uy = uy * decay - spec.gravity();
            uz *= decay;
        }
        double f = ticks - whole;
        return new Vec3[]{new Vec3(px + ux * f, py + uy * f, pz + uz * f), new Vec3(ux, uy, uz)};
    }

    private int last() {
        return x.length - 1;
    }

    /** False: life ends before step {@code k}. */
    private boolean reach(int k) {
        if (k > last()) {
            return false;
        }
        for (; filled < k; filled++) {
            int i = filled;
            x[i + 1] = x[i] + vx[i];
            y[i + 1] = y[i] + vy[i];
            double decay = spec.decay(Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]));
            vx[i + 1] = vx[i] * decay;
            vy[i + 1] = vy[i] * decay - spec.gravity();
        }
        return true;
    }

    private double lerp(double[] a, double ticks) {
        if (!(ticks >= 0)) {
            return Double.NaN;
        }
        int k = (int) ticks;
        if (k == ticks ? !reach(k) : !reach(k + 1)) {
            return Double.NaN;
        }
        return k == ticks ? a[k] : a[k] + (a[k + 1] - a[k]) * (ticks - k);
    }

    private double at(double[] a, double ticks) {
        return ticks >= 0 && reach((int) ticks) ? a[(int) ticks] : Double.NaN;
    }

    @Override
    public double horizontalAt(double ticks) {
        return lerp(x, ticks);
    }

    @Override
    public double heightAt(double ticks) {
        return lerp(y, ticks);
    }

    @Override
    public double verticalSpeedAt(double ticks) {
        return at(vy, ticks);
    }

    @Override
    public double horizontalSpeedAt(double ticks) {
        return at(vx, ticks);
    }

    @Override
    public double maxHorizontal() {
        reach(last());
        return x[last()];
    }

    @Override
    public double ticksToHorizontal(double range) {
        if (vx[0] <= 0) {
            return Double.NaN;
        }
        int k = 0;
        while (x[k] < range) {
            if (!reach(++k)) {
                return Double.NaN;
            }
        }
        return k == 0 ? 0 : k - 1 + (range - x[k - 1]) / (x[k] - x[k - 1]);
    }

    @Override
    public double apexTicks() {
        int k = 0;
        while (vy[k] > 0 && reach(k + 1)) {
            k++;
        }
        return k;
    }

    @Override
    public double ticksToDescendTo(double height) {
        int apex = (int) apexTicks();
        if (y[apex] < height) {
            return apex == 0 && y[0] <= height ? 0 : Double.NaN;
        }
        for (int k = apex + 1; reach(k); k++) {
            if (y[k] <= height) {
                return y[k - 1] == y[k] ? k : k - 1 + (y[k - 1] - height) / (y[k - 1] - y[k]);
            }
        }
        return Double.NaN;
    }

}
