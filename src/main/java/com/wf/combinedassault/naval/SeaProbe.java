package com.wf.combinedassault.naval;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Columns sampled per tick: bow, stern, beam ends, centre; quarter points added past {@link #LONG_HULL}.
 * Yaw-only rotation, plan ignores pitch.
 */
public final class SeaProbe {

    /** Length past which the quarter points are sampled too. */
    private static final double LONG_HULL = 48.0;
    /** Fraction of the half-beam the beam columns sit at. */
    private static final double INSET = 0.85;
    /** Blocks the bow and stern columns sit inside the hull's ends, so a shoal ahead is seen as it is met. */
    private static final double END_INSET = 0.5;
    /** Fraction of columns that must find water for the hull to count as afloat. */
    private static final double AFLOAT_FRACTION = 0.5;
    /** Blocks ahead of the bow the lookahead column sits, at least; scaled up for long hulls. */
    private static final double LOOKAHEAD = 2.0;
    private static final double LOOKAHEAD_FRACTION = 0.1;

    private final WaterColumn column = new WaterColumn();
    private final ShipHydrodynamics.Environment environment = new ShipHydrodynamics.Environment();

    public int columns;
    public int wet;
    public int unloadedColumns;

    public ShipHydrodynamics.Environment environment() {
        return environment;
    }

    /**
     * Samples the sea under a hull.
     *
     * @param waterlineY the world Y of the hull's waterline mark
     * @param keelY the world Y of the keel
     */
    public ShipHydrodynamics.Environment probe(Level level, double x, double z, float yaw,
                                               double halfLength, double halfBeam,
                                               double waterlineY, double keelY) {
        double yawRad = Math.toRadians(yaw);
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double lx = fz, lz = -fx;
        double along = Math.max(halfLength - END_INSET, halfLength * 0.5);
        double across = halfBeam * INSET;

        double surfaceSum = 0;
        double minClearance = Double.POSITIVE_INFINITY;
        double minDepth = Double.POSITIVE_INFINITY;
        columns = 0;
        wet = 0;
        unloadedColumns = 0;
        boolean quarters = halfLength * 2 > LONG_HULL;
        int count = quarters ? 7 : 5;
        for (int i = 0; i < count; i++) {
            double a, b;
            switch (i) {
                case 0 -> { a = along; b = 0; }
                case 1 -> { a = -along; b = 0; }
                case 2 -> { a = 0; b = across; }
                case 3 -> { a = 0; b = -across; }
                case 4 -> { a = 0; b = 0; }
                case 5 -> { a = along * 0.5; b = 0; }
                default -> { a = -along * 0.5; b = 0; }
            }
            double px = x + fx * a + lx * b;
            double pz = z + fz * a + lz * b;
            columns++;
            if (!column.sample(level, px, pz, waterlineY)) {
                if (column.unloaded) {
                    unloadedColumns++;
                }
                continue;
            }
            wet++;
            surfaceSum += column.surfaceY;
            minClearance = Math.min(minClearance, keelY - column.bedY);
            minDepth = Math.min(minDepth, column.surfaceY - column.bedY);
        }
        // One column ahead of the bow, for the keel reading only: a shoal is reported before it is hit.
        double ahead = halfLength + Math.max(LOOKAHEAD, halfLength * 2 * LOOKAHEAD_FRACTION);
        if (column.sample(level, x + fx * ahead, z + fz * ahead, waterlineY)) {
            minClearance = Math.min(minClearance, keelY - column.bedY);
        }
        ShipHydrodynamics.Environment env = environment;
        if (wet == 0 && unloadedColumns == columns) {
            // Unloaded terrain: nothing known; keep last reading, don't flag beached.
            return env;
        }
        int known = columns - unloadedColumns;
        if (wet == 0 || wet < Mth.ceil(known * AFLOAT_FRACTION)) {
            env.afloat = false;
            env.surfaceY = Double.NaN;
            env.keelClearance = Double.NaN;
            env.waterDepth = Double.NaN;
            return env;
        }
        env.afloat = true;
        env.surfaceY = surfaceSum / wet;
        env.keelClearance = minClearance;
        env.waterDepth = minDepth;
        return env;
    }

}
