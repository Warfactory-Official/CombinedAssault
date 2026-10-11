package com.wf.combinedassault.indirect.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.FireArc;
import com.wf.combinedassault.indirect.FireSolution;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.indirect.Trajectory;
import com.wf.combinedassault.vehicle.PhysicsEngine;

/**
 * Checked against {@link Trajectory}, which {@link TrajectoryGameTest} ties to the entity's own
 * arithmetic: agreement here is agreement with the round that actually fires.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BallisticSolverGameTest {

    private static final String TEMPLATE = "empty";

    /** Blocks. */
    private static final double MISS_TOLERANCE = 0.05;

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void solutionPassesThroughTheTarget(GameTestHelper helper) {
        double[][] cases = {
                // speed, drag, range, rise; all within real ground reach.
                {15.0, 0.01, 200.0, 0.0},
                {15.0, 0.01, 600.0, 40.0},
                {15.0, 0.01, 900.0, -60.0},
                {15.0, 0.01, 1200.0, 0.0},
                {5.0, 0.01, 200.0, 0.0},
                {25.0, 0.005, 2500.0, 0.0},
                {40.0, 0.002, 8000.0, 120.0},
                {8.0, 0.0, 300.0, 0.0},
                {8.0, 0.0, 2600.0, 0.0},
        };
        for (double[] test : cases) {
            RoundSpec spec = new RoundSpec(test[0], test[1], PhysicsEngine.G, 20 * 60 * 20);
            for (FireArc arc : FireArc.values()) {
                FireSolution solution = BallisticSolver.solve(spec, test[2], test[3], arc);
                if (solution == null) {
                    helper.fail(arc + " had no solution for range " + test[2] + " rise " + test[3]
                            + " at muzzle " + test[0] + " drag " + test[1]);
                    return;
                }
                Trajectory trajectory = Trajectory.of(spec, solution.elevationDegrees());
                double ticks = trajectory.ticksToHorizontal(test[2]);
                double height = trajectory.heightAt(ticks);
                if (Math.abs(height - test[3]) > MISS_TOLERANCE) {
                    helper.fail(arc + " solution missed by " + (height - test[3]) + " blocks at range "
                            + test[2] + " (elevation " + solution.elevationDegrees() + ")");
                    return;
                }
                if (Math.abs(ticks - solution.flightTicks()) > 1.0E-6) {
                    helper.fail("reported flight time " + solution.flightTicks() + " is not " + ticks);
                    return;
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void highArcIsSteeperThanLow(GameTestHelper helper) {
        RoundSpec spec = new RoundSpec(15, 0.01, PhysicsEngine.G, 20 * 60 * 10);
        FireSolution low = BallisticSolver.solve(spec, 500, 0, FireArc.LOW);
        FireSolution high = BallisticSolver.solve(spec, 500, 0, FireArc.HIGH);
        if (low == null || high == null) {
            helper.fail("both arcs should reach 500 blocks");
            return;
        }
        if (!(high.elevationDegrees() > low.elevationDegrees())) {
            helper.fail("high arc " + high.elevationDegrees() + " is not above low arc " + low.elevationDegrees());
            return;
        }
        if (!(high.flightTicks() > low.flightTicks())) {
            helper.fail("high arc should be the slower shot");
            return;
        }
        if (!(high.apexHeight() > low.apexHeight())) {
            helper.fail("high arc should be the taller shot");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void unreachableTargetHasNoSolution(GameTestHelper helper) {
        RoundSpec spec = new RoundSpec(15, 0.01, PhysicsEngine.G, 20 * 60 * 10);
        double reach = BallisticSolver.maxRange(spec);
        for (FireArc arc : FireArc.values()) {
            if (BallisticSolver.solve(spec, reach * 1.05, 0, arc) != null) {
                helper.fail("solved " + arc + " past the round's furthest reach of " + reach);
                return;
            }
        }
        // overhead target higher than the round can climb: the other way to be unreachable.
        if (BallisticSolver.solve(spec, 10, 100_000, FireArc.HIGH) != null) {
            helper.fail("solved a target the round cannot climb to");
            return;
        }
        helper.succeed();
    }

    /** Regression: unchecked bisection reported a solution missing by 743 blocks before the answer was verified. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void everySolutionReturnedActuallyHits(GameTestHelper helper) {
        RoundSpec spec = new RoundSpec(15, 0.01, PhysicsEngine.G, 20 * 60 * 10);
        for (double range = 20; range < 1600; range += 20) {
            for (double rise : new double[]{-80, 0, 60}) {
                for (FireArc arc : FireArc.values()) {
                    FireSolution solution = BallisticSolver.solve(spec, range, rise, arc);
                    if (solution == null) {
                        continue;
                    }
                    Trajectory trajectory = Trajectory.of(spec, solution.elevationDegrees());
                    double ticks = trajectory.ticksToHorizontal(range);
                    double missed = Math.abs(trajectory.heightAt(ticks) - rise);
                    if (!(missed <= MISS_TOLERANCE)) {
                        helper.fail(arc + " at range " + range + " rise " + rise + " returned elevation "
                                + solution.elevationDegrees() + " which misses by " + missed);
                        return;
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * maxRange is short of the drag asymptote (reaching it takes infinite time); a range just inside
     * it must still solve.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void maxRangeIsReachable(GameTestHelper helper) {
        RoundSpec spec = new RoundSpec(15, 0.01, PhysicsEngine.G, 20 * 60 * 10);
        double reach = BallisticSolver.maxRange(spec);
        double asymptote = spec.muzzleSpeed() / (1 - spec.decay());
        if (reach >= asymptote) {
            helper.fail("max range " + reach + " is not short of the drag asymptote " + asymptote);
            return;
        }
        if (BallisticSolver.solve(spec, reach * 0.98, 0, FireArc.LOW) == null) {
            helper.fail("a range just inside the reported maximum " + reach + " had no solution");
            return;
        }
        helper.succeed();
    }

    /** Core feature: at artillery range a tenth of a degree is a real distance; mount and solver must resolve one. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void tenthOfADegreeMovesTheImpact(GameTestHelper helper) {
        RoundSpec spec = new RoundSpec(25, 0.005, PhysicsEngine.G, 20 * 60 * 20);
        double range = 2000;
        FireSolution solution = BallisticSolver.solve(spec, range, 0, FireArc.HIGH);
        if (solution == null) {
            helper.fail("no solution at " + range);
            return;
        }
        double onTarget = impactRange(spec, solution.elevationDegrees());
        double nudged = impactRange(spec, solution.elevationDegrees() + 0.1);
        double moved = Math.abs(nudged - onTarget);
        if (moved < 1.0) {
            helper.fail("a tenth of a degree moved the impact only " + moved + " blocks at " + range
                    + "; the step is not worth having");
            return;
        }
        // a whole degree must move it more, or the fine step wouldn't be worth reaching for.
        double coarse = Math.abs(impactRange(spec, solution.elevationDegrees() + 1.0) - onTarget);
        if (coarse <= moved) {
            helper.fail("a degree moved the impact " + coarse + ", no more than a tenth did");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void layingQuantisesToItsStep(GameTestHelper helper) {
        GunLaying laying = new GunLaying();
        while (laying.step().size() != 0.1 || laying.step().inBlocks()) {
            laying.cycleStep();
        }
        laying.setElevation(45.0);
        laying.nudgeElevation(1);
        if (Math.abs(laying.elevation() - 45.1) > 1.0E-9) {
            helper.fail("one fine step from 45 should be 45.1, was " + laying.elevation());
            return;
        }
        laying.nudgeElevation(-1);
        if (Math.abs(laying.elevation() - 45.0) > 1.0E-9) {
            helper.fail("up and back down should return to 45, was " + laying.elevation());
            return;
        }
        // Off-grid figures are pulled onto the grid rather than drifting off it.
        laying.setElevation(45.037);
        laying.nudgeElevation(1);
        if (Math.abs(laying.elevation() - 45.1) > 1.0E-9) {
            helper.fail("a step from 45.037 should land on 45.1, was " + laying.elevation());
            return;
        }
        while (laying.stepIndex() != GunLaying.STEPS.length - 1) {
            laying.cycleStep();
        }
        laying.cycleStep();
        if (laying.step() != GunLaying.STEPS[0]) {
            helper.fail("cycling past the last rung of the ladder should wrap to the first");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void layingSolvesAndPointsAtTheTarget(GameTestHelper helper) {
        RoundSpec spec = new RoundSpec(25, 0.005, PhysicsEngine.G, 20 * 60 * 20);
        GunLaying laying = new GunLaying();
        laying.setMode(com.wf.combinedassault.indirect.LayingMode.MANUAL);
        Vec3 muzzle = new Vec3(0, 70, 0);
        Vec3 target = new Vec3(1200, 70, -900);
        if (!laying.solveTo(spec, muzzle, target)) {
            helper.fail("no solution for a target well inside the round's reach");
            return;
        }
        // direction in plan must point at the target regardless of elevation.
        Vec3 direction = laying.direction();
        Vec3 toTarget = target.subtract(muzzle);
        double laidBearing = Math.atan2(direction.x, direction.z);
        double trueBearing = Math.atan2(toTarget.x, toTarget.z);
        double bearingError = Math.toDegrees(Math.abs(Math.atan2(Math.sin(laidBearing - trueBearing),
                Math.cos(laidBearing - trueBearing))));
        if (bearingError > 0.01) {
            helper.fail("laid bearing is " + bearingError + " degrees off the target");
            return;
        }
        if (direction.y <= 0) {
            helper.fail("a high arc onto a level target should be laid above the horizon");
            return;
        }
        double elevationOfDirection = Math.toDegrees(Math.asin(direction.y / direction.length()));
        if (Math.abs(elevationOfDirection - laying.elevation()) > 0.01) {
            helper.fail("direction elevation " + elevationOfDirection + " does not match the laying "
                    + laying.elevation());
            return;
        }
        helper.succeed();
    }

    private static double impactRange(RoundSpec spec, double elevation) {
        Trajectory trajectory = Trajectory.of(spec, elevation);
        double ticks = trajectory.ticksToDescendTo(0);
        return Double.isFinite(ticks) ? trajectory.horizontalAt(ticks) : Double.NaN;
    }

}
