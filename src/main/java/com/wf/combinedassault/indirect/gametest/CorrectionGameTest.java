package com.wf.combinedassault.indirect.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.FireArc;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.PhysicsEngine;

/** Only promise checked: round moves by the blocks asked for, staying on the arc it was already on. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class CorrectionGameTest {

    private static final String TEMPLATE = "empty";

    private static final RoundSpec MORTAR = new RoundSpec(6.0, 0.01, PhysicsEngine.G, 1200);
    /** A degree is worth ~4x the ground here vs {@link #MORTAR}. */
    private static final RoundSpec ASSISTED = new RoundSpec(9.0, 0.004, PhysicsEngine.G, 2400);

    /** Blocks. */
    private static final double TOLERANCE = 0.05;

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aBlockOfCorrectionMovesTheImpactOneBlock(GameTestHelper helper) {
        for (RoundSpec round : new RoundSpec[]{MORTAR, ASSISTED}) {
            GunLaying laying = laid(round, 62.0, 5);
            double from = BallisticSolver.groundRange(round, laying.elevation());
            for (int press = 1; press <= 10; press++) {
                if (!laying.correctRange(round, Vec3.ZERO, 1)) {
                    helper.fail("a one block correction was refused at " + laying.elevation() + " degrees");
                    return;
                }
                double now = BallisticSolver.groundRange(round, laying.elevation());
                if (Math.abs(now - (from + press)) > TOLERANCE) {
                    helper.fail("after " + press + " presses the round lands at " + now
                            + ", not " + (from + press));
                    return;
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aCorrectionStaysOnTheArcItStartedOn(GameTestHelper helper) {
        double best = BallisticSolver.bestElevation(MORTAR);
        // both arcs, both directions: elevation moves opposite ways on each. Peaks ~28 deg; 20 is most of the low arc.
        double[][] cases = {{70.0, 50.0}, {70.0, -50.0}, {10.0, 50.0}, {10.0, -50.0}};
        for (double[] test : cases) {
            GunLaying laying = laid(MORTAR, test[0], 3);
            boolean high = test[0] > best;
            if (!laying.correctRange(MORTAR, Vec3.ZERO, test[1])) {
                helper.fail("a correction of " + test[1] + " from " + test[0] + " degrees was refused");
                return;
            }
            if (laying.elevation() > best != high) {
                helper.fail("a correction of " + test[1] + " from " + test[0]
                        + " degrees crossed the peak at " + best + " and came out at " + laying.elevation());
                return;
            }
            double moved = BallisticSolver.groundRange(MORTAR, laying.elevation())
                    - BallisticSolver.groundRange(MORTAR, test[0]);
            if (Math.signum(moved) != Math.signum(test[1])) {
                helper.fail("asked for " + test[1] + " blocks and the round moved " + moved);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void correctionsAreRelativeAndAddUp(GameTestHelper helper) {
        GunLaying laying = laid(MORTAR, 62.0, 3);
        double from = BallisticSolver.groundRange(MORTAR, laying.elevation());
        for (int press = 0; press < 3; press++) {
            if (!laying.correctRange(MORTAR, Vec3.ZERO, 50)) {
                helper.fail("a fifty block correction was refused at " + laying.elevation());
                return;
            }
        }
        double walked = BallisticSolver.groundRange(MORTAR, laying.elevation()) - from;
        if (Math.abs(walked - 150) > TOLERANCE) {
            helper.fail("three fifties walked the round " + walked + " blocks");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void acorrectionOffTheEndOfTheReachIsRefusedAndMovesNothing(GameTestHelper helper) {
        GunLaying laying = laid(MORTAR, BallisticSolver.bestElevation(MORTAR), 3);
        double was = laying.elevation();
        if (laying.correctRange(MORTAR, Vec3.ZERO, 500)) {
            helper.fail("a gun already at its maximum range accepted five hundred blocks more");
            return;
        }
        if (laying.elevation() != was) {
            helper.fail("a refused correction moved the gun from " + was + " to " + laying.elevation());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aLineCorrectionTurnsTheGunByTheAngleTheOffsetSubtends(GameTestHelper helper) {
        GunLaying laying = laid(MORTAR, 62.0, 3);
        laying.setAzimuth(0);
        double range = BallisticSolver.groundRange(MORTAR, laying.elevation());
        if (!laying.correctLine(MORTAR, Vec3.ZERO, 20)) {
            helper.fail("a twenty block line correction was refused");
            return;
        }
        double wanted = Math.toDegrees(Math.atan2(20, range));
        if (Math.abs(laying.azimuth() - wanted) > 1.0E-6) {
            helper.fail("twenty blocks at " + range + " is " + wanted + " degrees, the gun turned "
                    + laying.azimuth());
            return;
        }
        // positive = gunner's right (bearing convention).
        if (laying.azimuth() <= 0) {
            helper.fail("a correction to the right turned the gun " + laying.azimuth() + " degrees");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void correctingAGunLaidOnAPointMovesThePoint(GameTestHelper helper) {
        GunLaying laying = new GunLaying();
        laying.setMode(com.wf.combinedassault.indirect.LayingMode.MANUAL);
        laying.setArc(FireArc.HIGH);
        Vec3 muzzle = new Vec3(0, 70, 0);
        Vec3 target = new Vec3(0, 70, 250);
        if (!laying.solveTo(MORTAR, muzzle, target)) {
            helper.fail("the mortar cannot reach 250 blocks, which the rest of this test assumes");
            return;
        }
        if (!laying.correctRange(MORTAR, muzzle, 40)) {
            helper.fail("a forty block correction on a laid target was refused");
            return;
        }
        Vec3 moved = laying.target();
        if (moved == null || Math.abs(moved.distanceTo(target) - 40) > 1.0E-6) {
            helper.fail("the point moved to " + moved + " from " + target);
            return;
        }
        if (laying.solution() == null || Math.abs(laying.solution().range() - 290) > 1.0E-6) {
            helper.fail("the gun is now laid on " + (laying.solution() == null ? "nothing"
                    : laying.solution().range()) + " rather than 290");
            return;
        }
        // height of the point travels with it: that's the point of moving the point.
        if (Math.abs(moved.y - target.y) > 1.0E-9) {
            helper.fail("the corrected point changed height, from " + target.y + " to " + moved.y);
            return;
        }
        helper.succeed();
    }

    /** A hand-laid gun on the given elevation, with the ladder wound to the rung at {@code rung}. */
    private static GunLaying laid(RoundSpec spec, double elevation, int rung) {
        GunLaying laying = new GunLaying();
        laying.setMode(com.wf.combinedassault.indirect.LayingMode.MANUAL);
        laying.setArc(elevation > BallisticSolver.bestElevation(spec) ? FireArc.HIGH : FireArc.LOW);
        laying.setElevation(elevation);
        while (laying.stepIndex() != rung) {
            laying.cycleStep();
        }
        return laying;
    }

}
