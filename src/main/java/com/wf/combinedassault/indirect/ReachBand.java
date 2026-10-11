package com.wf.combinedassault.indirect;

/**
 * Range window a mount's stops leave usable on one arc. 81mm bipod floor 55 deg gives up ~1/3 of reach.
 * Band wholly on one side of {@link BallisticSolver#bestElevation} peak => other arc has nothing.
 *
 * @param nearest  blocks
 * @param farthest blocks
 * @param best     degrees above horizon; divides the arcs
 * @param maxRange blocks; round's reach with no stops
 */
public record ReachBand(double nearest, double farthest, double best, double maxRange) {

    /** {@code lowest}/{@code highest}: mount's elevation stops, degrees above horizon. */
    public static ReachBand of(RoundSpec spec, double lowest, double highest, FireArc arc) {
        double best = BallisticSolver.bestElevation(spec);
        double reach = BallisticSolver.groundRange(spec, best);
        double low = arc == FireArc.HIGH ? Math.max(lowest, best) : lowest;
        double high = arc == FireArc.HIGH ? highest : Math.min(highest, best);
        if (high - low < 1.0E-6) {
            return new ReachBand(0, 0, best, reach);
        }
        double atLow = BallisticSolver.groundRange(spec, low);
        double atHigh = BallisticSolver.groundRange(spec, high);
        return new ReachBand(Math.min(atLow, atHigh), Math.max(atLow, atHigh), best, reach);
    }

    /** False => arc unusable; tell the gunner, don't draw it. */
    public boolean covered() {
        return farthest > nearest;
    }

    /** 0..1 fraction, not percent. */
    public double givenUp() {
        return maxRange <= 0 ? 0 : 1.0 - farthest / maxRange;
    }

}
