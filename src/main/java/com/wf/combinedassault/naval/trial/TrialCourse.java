package com.wf.combinedassault.naval.trial;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.naval.WaterColumn;
import com.wf.wflib.physics.api.DeckSnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Channel course cut into open sea.
 *
 * <p>Navigable water = union of one capsule per leg, joint filleted by the channel half-width.
 * Outside it: vertical bank keel-to-waterline, not a dredged slope (~100x the blocks).
 */
public final class TrialCourse {

    /**
     * One leg. Turn first, then run.
     *
     * @param turnDegrees positive = to starboard
     * @param clearance multiple of the hull's min clearance; {@code <1} drags, {@code <0} bed above
     *                  keel. Bed quantized to blocks; trim swings the ends past nominal (carrier at
     *                  0.3 deg => 0.87 blocks; half a block of margin grounds her)
     */
    public record Leg(String name, double turnDegrees, double lengthHulls,
                      double halfWidthBeams, double clearance) {
    }

    /** Every leg passable; a leg that stops being passable is the regression. */
    public static final List<Leg> APPROACH = List.of(
            new Leg("seaward approach", 0, 0.9, 2.5, 6.0),
            new Leg("outer channel", -20, 1.2, 2.0, 3.0),
            new Leg("the narrows", 25, 1.0, 1.6, 1.5),
            new Leg("the shoal", 0, 0.8, 1.6, 0.6),
            new Leg("inner channel", -25, 1.1, 1.8, 1.2),
            new Leg("the bar", 0, 0.5, 1.8, 0.6),
            new Leg("the basin", 0, 0.8, 3.0, 5.0));

    /** Deliberately bed-above-keel: tests grounding + recovery astern. */
    public static final List<Leg> BAR = List.of(
            new Leg("run up", 0, 1.2, 2.5, 6.0),
            new Leg("the bar", 0, 1.0, 2.5, -0.3));

    /** Bank band width >=2: narrower leaks at a diagonal (one column's step exceeds the band). */
    private static final int BANK_THICKNESS = 2;
    /** >=2 layers: a hull taking the ground must not punch through the bed. */
    private static final int BED_LAYERS = 2;
    private static final double BANK_FOOT = 3.0;
    private static final double BANK_HEAD = 3.0;
    /** Caps one build; guards against a mistyped hull size filling the world. */
    private static final int MAX_COLUMNS = 6_000_000;

    private static final BlockState BED = Blocks.STONE.defaultBlockState();
    private static final BlockState BANK = Blocks.STONE.defaultBlockState();
    private static final BlockState SEA = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public record Mark(String leg, double x, double z, double heading,
                       double halfWidth, double clearance) {
    }

    public record Built(String name, List<Mark> marks, Vec3 start, float heading, double surfaceY,
                        double hullLength, double beam, int columns, int blocks, long millis) {

        public double length() {
            double total = 0;
            for (int i = 1; i < marks.size(); i++) {
                total += Math.hypot(marks.get(i).x() - marks.get(i - 1).x(),
                        marks.get(i).z() - marks.get(i - 1).z());
            }
            return total;
        }

        public String describe() {
            return String.format(Locale.ROOT,
                    "course %s: %d legs, %.0f blocks of channel for a hull %.0f by %.0f, "
                            + "surface y=%.2f, %d columns and %d blocks written in %d ms",
                    name, marks.size() - 1, length(), hullLength, beam, surfaceY, columns, blocks, millis);
        }
    }

    private record Capsule(double ax, double az, double bx, double bz,
                           double halfWidth, double bedTop) {

        double distanceSqr(double x, double z) {
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSqr = dx * dx + dz * dz;
            double t = lengthSqr <= 0 ? 0 : ((x - ax) * dx + (z - az) * dz) / lengthSqr;
            t = t < 0 ? 0 : Math.min(t, 1);
            double px = ax + dx * t - x;
            double pz = az + dz * t - z;
            return px * px + pz * pz;
        }
    }

    private TrialCourse() {
    }

    /**
     * @return {length, beam, height} from collision boxes, not {@code mainCubeOBB} (one cube of the
     *         structure, ~half a carrier => a course cut to it would put the bank in her own stern)
     */
    public static double[] hullSize(AbstractVehicle vehicle) {
        DeckSnapshot deck = vehicle.deckSnapshot();
        float[] boxes = deck.boxes();
        int count = deck.count();
        if (count == 0) {
            return null;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            int o = i * 6;
            minX = Math.min(minX, boxes[o]);
            minY = Math.min(minY, boxes[o + 1]);
            minZ = Math.min(minZ, boxes[o + 2]);
            maxX = Math.max(maxX, boxes[o + 3]);
            maxY = Math.max(maxY, boxes[o + 4]);
            maxZ = Math.max(maxZ, boxes[o + 5]);
        }
        return new double[]{maxZ - minZ, maxX - minX, maxY - minY};
    }

    public static List<Leg> byName(String name) {
        return switch (name) {
            case "bar" -> BAR;
            default -> APPROACH;
        };
    }

    /**
     * @param draft blocks from waterline to keel
     * @param minClearance ties to {@link Leg#clearance}: the dragging threshold
     */
    public static Built build(ServerLevel level, String name, List<Leg> legs, Vec3 origin, float heading,
                              double hullLength, double beam, double draft, double minClearance) {
        long began = System.currentTimeMillis();
        double surfaceY = surfaceAt(level, origin);
        if (Double.isNaN(surfaceY)) {
            return null;
        }

        List<Mark> marks = new ArrayList<>();
        List<Capsule> capsules = new ArrayList<>();
        double x = origin.x;
        double z = origin.z;
        double course = heading;
        marks.add(new Mark(legs.get(0).name(), x, z, course,
                legs.get(0).halfWidthBeams() * beam, legs.get(0).clearance() * minClearance));
        for (Leg leg : legs) {
            course += leg.turnDegrees();
            double radians = Math.toRadians(course);
            double fx = -Math.sin(radians);
            double fz = Math.cos(radians);
            double run = leg.lengthHulls() * hullLength;
            double halfWidth = leg.halfWidthBeams() * beam;
            double clearance = leg.clearance() * minClearance;
            double ex = x + fx * run;
            double ez = z + fz * run;
            capsules.add(new Capsule(x, z, ex, ez, halfWidth, surfaceY - draft - clearance));
            x = ex;
            z = ez;
            marks.add(new Mark(leg.name(), x, z, course, halfWidth, clearance));
        }

        int[] written = carve(level, capsules, surfaceY, draft);
        if (written == null) {
            return null;
        }
        return new Built(name, marks, new Vec3(origin.x, surfaceY, origin.z), heading, surfaceY,
                hullLength, beam, written[0], written[1], System.currentTimeMillis() - began);
    }

    /** @return NaN when there is no water at the start point */
    private static double surfaceAt(ServerLevel level, Vec3 origin) {
        level.getChunk(BlockPos.containing(origin));
        WaterColumn column = new WaterColumn();
        return column.sample(level, origin.x, origin.z, origin.y) ? column.surfaceY : Double.NaN;
    }

    /**
     * Writes direct to chunk sections, not {@code Level.setBlock}: the per-block light queue would
     * outlast the trial at this scale. Heightmaps primed per chunk instead (sea probe reads these);
     * skylight stays stale.
     *
     * @return {columns touched, blocks changed}
     */
    private static int[] carve(ServerLevel level, List<Capsule> capsules, double surfaceY, double draft) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Capsule capsule : capsules) {
            double margin = capsule.halfWidth() + BANK_THICKNESS + 1;
            minX = Math.min(minX, (int) Math.floor(Math.min(capsule.ax(), capsule.bx()) - margin));
            maxX = Math.max(maxX, (int) Math.ceil(Math.max(capsule.ax(), capsule.bx()) + margin));
            minZ = Math.min(minZ, (int) Math.floor(Math.min(capsule.az(), capsule.bz()) - margin));
            maxZ = Math.max(maxZ, (int) Math.ceil(Math.max(capsule.az(), capsule.bz()) + margin));
        }
        long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > MAX_COLUMNS) {
            return null;
        }

        int bankFoot = (int) Math.floor(surfaceY - draft - BANK_FOOT);
        int bankHead = (int) Math.ceil(surfaceY + BANK_HEAD);
        int seaTop = (int) Math.floor(surfaceY);
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        int touched = 0;
        int blocks = 0;

        for (int wx = minX; wx <= maxX; wx++) {
            for (int wz = minZ; wz <= maxZ; wz++) {
                // Nearest centreline wins on overlap, not deepest: a capsule's end-cap spills a
                // half-width past its own mark, so a short leg (the shoal, the bar - the course's
                // point) would else be dredged away by a wider neighbour.
                double bedTop = Double.NaN;
                double nearestSqr = Double.MAX_VALUE;
                boolean inside = false;
                boolean near = false;
                for (int i = 0, size = capsules.size(); i < size; i++) {
                    Capsule capsule = capsules.get(i);
                    double distanceSqr = capsule.distanceSqr(wx + 0.5, wz + 0.5);
                    double half = capsule.halfWidth();
                    if (distanceSqr <= half * half) {
                        inside = true;
                        if (distanceSqr < nearestSqr) {
                            nearestSqr = distanceSqr;
                            bedTop = capsule.bedTop();
                        }
                    } else if (distanceSqr <= (half + BANK_THICKNESS) * (half + BANK_THICKNESS)) {
                        near = true;
                    }
                }
                if (!inside && !near) {
                    continue;
                }
                int cx = wx >> 4;
                int cz = wz >> 4;
                if (chunk == null || cx != chunkX || cz != chunkZ) {
                    if (chunk != null) {
                        settle(chunk);
                    }
                    chunk = level.getChunk(cx, cz);
                    chunkX = cx;
                    chunkZ = cz;
                }
                touched++;
                if (inside) {
                    // Slab, not full dredge: the top face is all the sea probe reads; nothing
                    // floating ever reaches what's left under it.
                    int top = (int) Math.round(bedTop) - 1;
                    for (int y = top; y > top - BED_LAYERS; y--) {
                        blocks += put(chunk, wx, y, wz, BED);
                    }
                    // Refilled with sea/air so a rebuild over an older course replaces it rather
                    // than unions with it.
                    for (int y = top + 1; y <= bankHead; y++) {
                        blocks += put(chunk, wx, y, wz, y <= seaTop ? SEA : AIR);
                    }
                } else {
                    for (int y = bankFoot; y <= bankHead; y++) {
                        blocks += put(chunk, wx, y, wz, BANK);
                    }
                }
            }
        }
        if (chunk != null) {
            settle(chunk);
        }
        return new int[]{touched, blocks};
    }

    /** @return 1 iff the block changed; a rebuild of an unchanged spec reports 0 */
    private static int put(LevelChunk chunk, int x, int y, int z, BlockState state) {
        int index = chunk.getSectionIndex(y);
        if (index < 0 || index >= chunk.getSections().length) {
            return 0;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.getBlockState(x & 15, y & 15, z & 15) == state) {
            return 0;
        }
        section.setBlockState(x & 15, y & 15, z & 15, state, false);
        return 1;
    }

    /** Primed once per touched chunk, not per block. */
    private static void settle(LevelChunk chunk) {
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));
        chunk.setUnsaved(true);
    }

}
