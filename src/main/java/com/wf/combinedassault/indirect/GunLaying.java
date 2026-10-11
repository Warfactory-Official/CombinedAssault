package com.wf.combinedassault.indirect;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.util.VectorUtil;

/**
 * Elevation/azimuth as numbers, not derived from a camera: the mount is laid to a repeatable figure.
 * Tenth-degree finest step: at 4 km that's ~7 blocks of range. Corrections are in blocks (fall of shot),
 * not degrees; {@link #elevate}/{@link #traverse} walk one ladder spanning both units.
 *
 * <p>Knows nothing of part units, vehicles or the world: testable without them.
 */
public class GunLaying {

    /**
     * @param size     degrees, or blocks of impact when {@code inBlocks}
     * @param inBlocks true: press moves fall of shot, not barrel
     */
    public record Step(double size, boolean inBlocks) {
    }

    /**
     * Coarse to fine; units change partway. Tenth-degree floor: on the shipped 81mm that's ~0.8 block of
     * impact, already inside round dispersion. Bottom rungs in blocks of impact, not degrees: a given
     * block correction is a different angle at 300 blocks than at 800 with a rocket-assisted shell.
     */
    public static final Step[] STEPS = {
            new Step(10.0, false), new Step(1.0, false), new Step(0.1, false),
            new Step(50.0, true), new Step(10.0, true), new Step(1.0, true)};

    /** Off-sight gun is being laid, not corrected: starts at the finest angle rung. */
    private static final int DEFAULT_STEP = 2;

    private static final double MIN_ELEVATION = -89.9;
    private static final double MAX_ELEVATION = 89.9;

    private LayingMode mode = LayingMode.DIRECT;
    private FireArc arc = FireArc.HIGH;
    private double elevation;
    private double azimuth;
    private int step = DEFAULT_STEP;

    /** Null: hand-laid, no solved point. */
    @Nullable
    private Vec3 target;

    /** Null: current numbers were dialled in by hand. */
    @Nullable
    private FireSolution solution;

    public boolean isIndirect() {
        return mode != LayingMode.DIRECT;
    }

    public LayingMode mode() {
        return mode;
    }

    public void setMode(LayingMode mode) {
        this.mode = mode;
    }

    /** Self-guided round: bearing to {@code target}, fixed launch elevation; no ballistic solution. */
    public void designate(Vec3 muzzle, Vec3 target, double elevationDegrees) {
        this.target = target;
        this.solution = null;
        setElevation(elevationDegrees);
        setAzimuth(Math.toDegrees(Math.atan2(-(target.x - muzzle.x), target.z - muzzle.z)));
    }

    public FireArc arc() {
        return arc;
    }

    public void setArc(FireArc arc) {
        this.arc = arc;
    }

    /**
     * Re-lays on the other arc: same ground, different elevation. Target set => resolved onto it;
     * hand-laid => range held, elevation recomputed for the new arc.
     *
     * @return false if the other arc can't make the same ground; gun unchanged
     */
    public boolean toggleArc(RoundSpec spec, Vec3 muzzle) {
        FireArc wanted = arc.other();
        if (target != null) {
            FireArc had = arc;
            this.arc = wanted;
            if (solveTo(spec, muzzle, target)) {
                return true;
            }
            this.arc = had;
            return false;
        }
        FireSolution solved = BallisticSolver.solve(spec, BallisticSolver.groundRange(spec, elevation),
                0, wanted);
        if (solved == null) {
            return false;
        }
        this.arc = wanted;
        setElevation(solved.elevationDegrees());
        return true;
    }

    public double elevation() {
        return elevation;
    }

    public double azimuth() {
        return azimuth;
    }

    public Step step() {
        return STEPS[step];
    }

    public int stepIndex() {
        return step;
    }

    public void cycleStep() {
        step = (step + 1) % STEPS.length;
    }

    @Nullable
    public Vec3 target() {
        return target;
    }

    @Nullable
    public FireSolution solution() {
        return solution;
    }

    /** Quantised to the step, not added: up-then-down returns to the exact start. */
    public void nudgeElevation(int steps) {
        double size = step().size();
        setElevation(quantise(elevation + steps * size, size));
        clearTarget();
    }

    public void nudgeAzimuth(int steps) {
        double size = step().size();
        setAzimuth(quantise(azimuth + steps * size, size));
        clearTarget();
    }

    /** @return false only if a blocks correction has no answer; gun unmoved */
    public boolean elevate(@Nullable RoundSpec spec, Vec3 muzzle, int presses) {
        Step rung = step();
        if (!rung.inBlocks()) {
            nudgeElevation(presses);
            return true;
        }
        return spec != null && correctRange(spec, muzzle, presses * rung.size());
    }

    /** Mirrors {@link #elevate}. */
    public boolean traverse(@Nullable RoundSpec spec, Vec3 muzzle, int presses) {
        Step rung = step();
        if (!rung.inBlocks()) {
            nudgeAzimuth(presses);
            return true;
        }
        return spec != null && correctLine(spec, muzzle, presses * rung.size());
    }

    /**
     * Fall of shot along the line of fire; positive = further out. Target set => point moved (height
     * carried, bearing re-solved from the gun); hand-laid => correction applied to level-ground range.
     */
    public boolean correctRange(RoundSpec spec, Vec3 muzzle, double blocks) {
        if (target != null) {
            return solveTo(spec, muzzle,
                    target.add(VectorUtil.rotToVec(0, (float) azimuth).normalize().scale(blocks)));
        }
        // not quantised, unlike an angle step: "add fifty" means exactly fifty.
        double wanted = BallisticSolver.groundRange(spec, elevation) + blocks;
        double solved = BallisticSolver.elevationForRange(spec, elevation, wanted);
        if (Double.isNaN(solved)) {
            return false;
        }
        setElevation(solved);
        return true;
    }

    /** Fall of shot across the line of fire; positive = gunner's right. */
    public boolean correctLine(RoundSpec spec, Vec3 muzzle, double blocks) {
        if (target != null) {
            return solveTo(spec, muzzle,
                    target.add(VectorUtil.rotToVec(0, (float) (azimuth + 90)).normalize().scale(blocks)));
        }
        double range = BallisticSolver.groundRange(spec, elevation);
        if (!(range > 0)) {
            return false;
        }
        setAzimuth(azimuth + Math.toDegrees(Math.atan2(blocks, range)));
        return true;
    }

    public void setElevation(double degrees) {
        this.elevation = Mth.clamp(degrees, MIN_ELEVATION, MAX_ELEVATION);
    }

    public void setAzimuth(double degrees) {
        this.azimuth = Mth.wrapDegrees(degrees);
    }

    /** @return false if unreachable; gun left where it was */
    public boolean solveTo(RoundSpec spec, Vec3 muzzle, Vec3 target) {
        FireSolution solved = BallisticSolver.solve(spec, muzzle, target, arc);
        if (solved == null) {
            return false;
        }
        this.target = target;
        this.solution = solved;
        setElevation(solved.elevationDegrees());
        setAzimuth(solved.azimuthDegrees());
        return true;
    }

    public void clearTarget() {
        this.target = null;
        this.solution = null;
    }

    /** The unit vector the barrel should be pointing along, in world space. */
    public Vec3 direction() {
        return VectorUtil.rotToVec((float) -elevation, (float) azimuth).normalize();
    }

    /** How far the mount is from the figure it has been laid to, in degrees. */
    public double error(double actualElevation, double actualAzimuth) {
        double de = elevation - actualElevation;
        double da = Mth.wrapDegrees(azimuth - actualAzimuth);
        return Math.max(Math.abs(de), Math.abs(da));
    }

    private static double quantise(double value, double step) {
        return Math.round(value / step) * step;
    }

}
