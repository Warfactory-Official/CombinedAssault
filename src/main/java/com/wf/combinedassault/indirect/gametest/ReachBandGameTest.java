package com.wf.combinedassault.indirect.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.FireArc;
import com.wf.combinedassault.indirect.FireSolution;
import com.wf.combinedassault.indirect.ReachBand;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.PhysicsEngine;

/** Range scale's window is a promise: a range inside it must lay without hitting a stop. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class ReachBandGameTest {

    private static final String TEMPLATE = "empty";

    /** Shipped 81mm round + the bipod's stops. */
    private static final RoundSpec MORTAR = new RoundSpec(6.0, 0.01, PhysicsEngine.G, 1200);
    private static final double FLOOR = 55.0;
    private static final double CEILING = 85.0;

    /** Degrees. */
    private static final double TOLERANCE = 0.01;

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void theBipodLeavesOneWindowAndItIsOnTheHighArc(GameTestHelper helper) {
        ReachBand high = ReachBand.of(MORTAR, FLOOR, CEILING, FireArc.HIGH);
        if (!high.covered()) {
            helper.fail("the bipod's stops cover nothing on the high arc");
            return;
        }
        if (!(high.nearest() > 0) || !(high.farthest() > high.nearest())) {
            helper.fail("the window runs " + high.nearest() + " to " + high.farthest());
            return;
        }
        // band wholly above the peak elevation: no low arc exists.
        ReachBand low = ReachBand.of(MORTAR, FLOOR, CEILING, FireArc.LOW);
        if (low.covered()) {
            helper.fail("a band that starts at " + FLOOR + " degrees, above the best elevation "
                    + high.best() + ", should leave no low arc, but it claims " + low.nearest()
                    + " to " + low.farthest());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void everyRangeInTheWindowLaysWithoutHittingAStop(GameTestHelper helper) {
        ReachBand window = ReachBand.of(MORTAR, FLOOR, CEILING, FireArc.HIGH);
        double span = window.farthest() - window.nearest();
        for (int step = 1; step < 25; step++) {
            double range = window.nearest() + span * step / 25.0;
            FireSolution solution = BallisticSolver.solve(MORTAR, range, 0, FireArc.HIGH);
            if (solution == null) {
                helper.fail("no high arc solution at " + range + " blocks, inside the drawn window "
                        + window.nearest() + " to " + window.farthest());
                return;
            }
            double elevation = solution.elevationDegrees();
            if (elevation < FLOOR - TOLERANCE || elevation > CEILING + TOLERANCE) {
                helper.fail("a range of " + range + " blocks is drawn as reachable but solves to "
                        + elevation + " degrees, outside the " + FLOOR + " to " + CEILING
                        + " the bipod carries");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aWindowNeverClaimsMoreThanTheRoundCanReach(GameTestHelper helper) {
        double[][] bands = {{FLOOR, CEILING}, {0, 89}, {-20, 40}, {70, 89}, {0, 15}};
        RoundSpec[] rounds = {MORTAR, new RoundSpec(15.0, 0.01, PhysicsEngine.G, 1200),
                new RoundSpec(9.0, 0.004, PhysicsEngine.G, 2400)};
        for (RoundSpec round : rounds) {
            double reach = BallisticSolver.maxRange(round);
            for (double[] band : bands) {
                for (FireArc arc : FireArc.values()) {
                    ReachBand window = ReachBand.of(round, band[0], band[1], arc);
                    if (window.farthest() > reach + 1.0E-6) {
                        helper.fail("stops " + band[0] + " to " + band[1] + " on the " + arc
                                + " arc claim " + window.farthest() + " blocks from a round that reaches "
                                + reach);
                        return;
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aMountThatStraddlesTheBestElevationGetsTheWholeReachOnBothArcs(GameTestHelper helper) {
        ReachBand high = ReachBand.of(MORTAR, 0, 89, FireArc.HIGH);
        ReachBand low = ReachBand.of(MORTAR, 0, 89, FireArc.LOW);
        double reach = BallisticSolver.maxRange(MORTAR);
        if (!high.covered() || !low.covered()) {
            helper.fail("a mount with no stops worth the name should have both arcs");
            return;
        }
        // blocks; both arcs meet at best elevation, so both windows end at the round's own reach.
        double tolerance = 0.5;
        if (Math.abs(high.farthest() - reach) > tolerance || Math.abs(low.farthest() - reach) > tolerance) {
            helper.fail("the arcs reach " + high.farthest() + " and " + low.farthest()
                    + " against the round's " + reach);
            return;
        }
        helper.succeed();
    }

}
