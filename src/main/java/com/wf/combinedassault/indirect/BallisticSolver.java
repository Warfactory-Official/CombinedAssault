package com.wf.combinedassault.indirect;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * "Hit that" -> elevation. No closed form once drag's in the model: height-at-range is single-peaked in
 * elevation, so peak search + bisect the requested arc's side. Searches the whole elevation domain, not
 * the mount's stops; a gun that can't reach says so by the number, comparing after solving.
 *
 * <p>Bisection always converges to <i>something</i>; unchecked, an unreachable arc returns a result
 * hundreds of blocks off target. Every answer is verified before return.
 */
public final class BallisticSolver {

    /** ±90 degenerates: cos -> 0 in the trig below. */
    private static final double MIN_ELEVATION = -89.0;
    private static final double MAX_ELEVATION = 89.0;

    /** Blocks. */
    private static final double CONVERGENCE_TOLERANCE = 0.01;

    private static final int PEAK_ITERATIONS = 200;
    private static final int BISECTIONS = 200;

    private static final int CORRECTION_STEPS = 8;
    private static final double CORRECTION_TOLERANCE = 0.01;
    private static final double SLOPE_STEP = 0.05;
    private static final double MIN_SLOPE = 0.02;
    private static final double MAX_CORRECTION = 15.0;

    private BallisticSolver() {
    }

    /**
     * @param range horizontal distance to the target, blocks
     * @param rise  target height minus muzzle height, blocks
     * @return the solution, or null if no elevation on this arc reaches it
     */
    @Nullable
    public static FireSolution solve(RoundSpec spec, double range, double rise, FireArc arc) {
        if (range <= 0 || spec.muzzleSpeed() <= 0) {
            return null;
        }
        double highest = elevationCeiling(spec, range);
        if (Double.isNaN(highest) || highest <= MIN_ELEVATION) {
            return null;
        }
        // reach symmetric in cos(elevation): -highest mirrors highest's horizontal speed/asymptote.
        double lowest = Math.max(MIN_ELEVATION, -highest);
        double peak = peakElevation(spec, range, lowest, highest);
        if (heightAtRange(spec, range, peak) < rise) {
            return null;
        }
        double low = arc == FireArc.LOW ? lowest : peak;
        double high = arc == FireArc.LOW ? peak : highest;
        // below peak: height rises with elevation; above: falls. Bisect accordingly.
        boolean risesWithElevation = arc == FireArc.LOW;
        for (int i = 0; i < BISECTIONS; i++) {
            double mid = (low + high) / 2;
            boolean over = heightAtRange(spec, range, mid) > rise;
            if (over == risesWithElevation) {
                high = mid;
            } else {
                low = mid;
            }
        }
        double elevation = (low + high) / 2;
        double landed = heightAtRange(spec, range, elevation);
        if (!Double.isFinite(landed) || Math.abs(landed - rise) > CONVERGENCE_TOLERANCE) {
            return null;
        }
        Trajectory trajectory = Trajectory.of(spec, elevation);
        double ticks = trajectory.ticksToHorizontal(range);
        if (!Double.isFinite(ticks)) {
            return null;
        }
        return new FireSolution(elevation, 0, ticks, trajectory.apexHeight(),
                trajectory.speedAt(ticks), range, arc);
    }

    /** As {@link #solve}, with the azimuth filled in and the geometry taken off two world positions. */
    @Nullable
    public static FireSolution solve(RoundSpec spec, Vec3 muzzle, Vec3 target, FireArc arc) {
        double dx = target.x - muzzle.x;
        double dz = target.z - muzzle.z;
        double range = Math.sqrt(dx * dx + dz * dz);
        FireSolution solution = solve(spec, range, target.y - muzzle.y, arc);
        return solution == null ? null : solution.withAzimuth(azimuth(dx, dz));
    }

    /** World yaw pointing along the given horizontal offset, in {@code VectorUtil.rotToVec} terms. */
    public static double azimuth(double dx, double dz) {
        return Math.toDegrees(-Math.atan2(dx, dz));
    }

    /**
     * Not the drag asymptote (unreachable in finite time): best any single elevation actually manages.
     * 15 b/t round losing 1%/tick: asymptote 1500, reaches 1311.
     */
    public static double maxRange(RoundSpec spec) {
        return groundRange(spec, bestElevation(spec));
    }

    /**
     * Not 45 deg: drag moves it down, round-dependent. Divides the two arcs: mount's stops wholly
     * above/below it => no low/high arc.
     */
    public static double bestElevation(RoundSpec spec) {
        double low = 0.1;
        double high = MAX_ELEVATION;
        for (int i = 0; i < PEAK_ITERATIONS; i++) {
            double third = (high - low) / 3;
            double left = low + third;
            double right = high - third;
            if (groundRange(spec, left) < groundRange(spec, right)) {
                low = left;
            } else {
                high = right;
            }
        }
        return (low + high) / 2;
    }

    /**
     * Nearest elevation to {@code from} landing at {@code range}, or NaN if none within reach of
     * {@code from}. Deliberately not {@link #solve}: that is a global search and could jump arcs (a 70
     * deg mortar dropping to 20 for the same range). This follows the local slope and never crosses the
     * peak it started on; a flat slope near the reach's end reads as refusal (NaN) rather than forcing
     * convergence.
     */
    public static double elevationForRange(RoundSpec spec, double from, double range) {
        if (range <= 0 || spec.muzzleSpeed() <= 0) {
            return Double.NaN;
        }
        double elevation = Mth.clamp(from, MIN_ELEVATION, MAX_ELEVATION);
        double side = 0;
        for (int i = 0; i < CORRECTION_STEPS; i++) {
            double error = range - groundRange(spec, elevation);
            if (Math.abs(error) < CORRECTION_TOLERANCE) {
                return elevation;
            }
            double slope = (groundRange(spec, elevation + SLOPE_STEP)
                    - groundRange(spec, elevation - SLOPE_STEP)) / (2 * SLOPE_STEP);
            if (!Double.isFinite(slope) || Math.abs(slope) < MIN_SLOPE) {
                return Double.NaN;
            }
            if (side == 0) {
                side = Math.signum(slope);
            } else if (Math.signum(slope) != side) {
                // slope sign flipped => crossed the peak onto the other arc; refuse.
                return Double.NaN;
            }
            elevation = Mth.clamp(elevation + Mth.clamp(error / slope, -MAX_CORRECTION, MAX_CORRECTION),
                    MIN_ELEVATION, MAX_ELEVATION);
        }
        return Math.abs(groundRange(spec, elevation) - range) < CORRECTION_TOLERANCE
                ? elevation : Double.NaN;
    }

    /** Where a round laid at this elevation comes back down to the height it left from. */
    public static double groundRange(RoundSpec spec, double elevationDegrees) {
        Trajectory trajectory = Trajectory.of(spec, elevationDegrees);
        double ticks = trajectory.ticksToDescendTo(0);
        return Double.isFinite(ticks) ? trajectory.horizontalAt(ticks) : 0;
    }

    /**
     * Highest elevation whose reach covers {@code range}, NaN if none. Exact asymptote, no margin: at 80
     * deg a round travels ~260 against an asymptote of 260, so even a 2% trim would drop the whole high
     * arc, not just its degenerate tail.
     */
    private static double elevationCeiling(RoundSpec spec, double range) {
        if (spec.drag() <= 0) {
            return MAX_ELEVATION;
        }
        double cosineNeeded = range * (1 - spec.decay()) / spec.muzzleSpeed();
        if (cosineNeeded >= 1) {
            return Double.NaN;
        }
        return Math.min(MAX_ELEVATION, Math.toDegrees(Math.acos(cosineNeeded)));
    }

    /** Ternary search over elevation for the one that peaks height over the target. */
    private static double peakElevation(RoundSpec spec, double range, double low, double high) {
        for (int i = 0; i < PEAK_ITERATIONS; i++) {
            double third = (high - low) / 3;
            double left = low + third;
            double right = high - third;
            if (heightAtRange(spec, range, left) < heightAtRange(spec, range, right)) {
                low = left;
            } else {
                high = right;
            }
        }
        return (low + high) / 2;
    }

    /**
     * Height at {@code range}, or {@code NEGATIVE_INFINITY} if unreached. Infinity, not NaN, so an
     * unreachable elevation compares correctly in the peak/bisection searches instead of poisoning them.
     */
    private static double heightAtRange(RoundSpec spec, double range, double elevationDegrees) {
        Trajectory trajectory = Trajectory.of(spec, elevationDegrees);
        double ticks = trajectory.ticksToHorizontal(range);
        if (!Double.isFinite(ticks) || ticks < 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return trajectory.heightAt(ticks);
    }

}
