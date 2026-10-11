package com.wf.combinedassault.tow.trial;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;

import java.util.EnumSet;
import java.util.List;

/** Ring course, sector-cut: one lap crosses broken ground AND a turn long enough to expose
 * off-tracking. Same point every lap => runs comparable. Heights are whole blocks: a one-block
 * kerb is a step for the contact solve, not a ramp rounding. */
public final class TowGround {

    private static final BlockState SURFACE = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState FILL = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final int FILL_LAYERS = 3;

    /** Clear-air headroom above track so old terrain over a crest is carved away, not left poking through. */
    private static final int HEADROOM = 12;

    /** Blocks of arc over which a sector's height tapers to 0 at its seams. */
    private static final double SEAM_RAMP = 8;

    private TowGround() {
    }

    public enum Surface {

        /** Control: flat, so a failure here isn't the ground's fault. */
        FLAT,
        /** Transverse ridges: pitch-hammers the trailer. */
        WASHBOARD,
        /** Longitudinal ridges: roll the tow doesn't control. */
        RUTS,
        /** Testing realistic noise, not a synthetic rig shape. */
        ROUGH,
        STEPS,
        /** Hill crest: a long trailer's nose and tail land on different slopes. */
        CREST;

        public String lower() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

    }

    public static final List<Surface> LAP = List.of(Surface.FLAT, Surface.WASHBOARD, Surface.RUTS,
            Surface.ROUGH, Surface.STEPS, Surface.CREST);

    public record Spec(String name, List<Surface> sectors, int radius, int halfWidth) {
    }

    public record Built(Spec spec, Vec3 centre, int baseY, Vec3 start, float heading) {

        /** Degrees clockwise from start. */
        public double bearing(double x, double z) {
            return Math.toDegrees(Math.atan2(x - centre.x, z - centre.z));
        }

        public Surface surfaceAt(double x, double z) {
            double turn = (bearing(x, z) + 360) % 360;
            int sector = (int) (turn / 360 * spec.sectors().size());
            return spec.sectors().get(Math.min(sector, spec.sectors().size() - 1));
        }

    }

    /** Presets: full lap, plus flat/rough as single-surface controls. */
    public static Spec spec(String name, int radius) {
        return switch (name) {
            case "flat" -> new Spec(name, List.of(Surface.FLAT), radius, 12);
            case "rough" -> new Spec(name, List.of(Surface.ROUGH), radius, 12);
            case "washboard" -> new Spec(name, List.of(Surface.WASHBOARD), radius, 12);
            default -> new Spec("lap", LAP, radius, 12);
        };
    }

    /** Writes through chunk sections directly (as the naval course does): per-block light/neighbour
     * updates would outlast the trial at this block count. Heightmaps primed once per chunk. */
    public static Built build(ServerLevel level, Vec3 centre, Spec spec) {
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(centre.x), (int) Math.floor(centre.z));
        int reach = spec.radius() + spec.halfWidth() + 2;
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        for (int x = (int) centre.x - reach; x <= centre.x + reach; x++) {
            for (int z = (int) centre.z - reach; z <= centre.z + reach; z++) {
                double offset = Math.hypot(x - centre.x, z - centre.z) - spec.radius();
                if (Math.abs(offset) > spec.halfWidth()) {
                    continue;
                }
                if (chunk == null || (x >> 4) != chunkX || (z >> 4) != chunkZ) {
                    if (chunk != null) {
                        settle(level, chunk);
                    }
                    chunkX = x >> 4;
                    chunkZ = z >> 4;
                    chunk = level.getChunk(chunkX, chunkZ);
                }
                double turn = (Math.toDegrees(Math.atan2(x - centre.x, z - centre.z)) + 360) % 360;
                int sector = Math.min((int) (turn / 360 * spec.sectors().size()), spec.sectors().size() - 1);
                double along = Math.toRadians(turn) * spec.radius();
                int raw = height(spec.sectors().get(sector), along, offset, x, z);
                if (spec.sectors().size() > 1) {
                    // Seams: sectors' heights tapered to 0; untapered, rough -1 met steps +2 (3-block wall), crest met flat 4 up.
                    double width = 2 * Math.PI * spec.radius() / spec.sectors().size();
                    double into = along - sector * width;
                    raw = (int) Math.round(raw * Math.min(1, Math.min(into, width - into) / SEAM_RAMP));
                }
                int top = baseY + raw;
                for (int y = top; y > top - FILL_LAYERS; y--) {
                    put(chunk, x, y, z, y == top ? SURFACE : FILL);
                }
                for (int y = top + 1; y <= baseY + HEADROOM; y++) {
                    put(chunk, x, y, z, AIR);
                }
            }
        }
        if (chunk != null) {
            settle(level, chunk);
        }
        // Start at sector MIDPOINT not edge: sector before FLAT is CREST; a seam-start drops a
        // 16-block train's tail into the hill, and the run measures recovery, not the surface under test.
        double start = Math.toRadians(180.0 / spec.sectors().size());
        double startX = centre.x + spec.radius() * Math.sin(start);
        double startZ = centre.z + spec.radius() * Math.cos(start);
        int startY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(startX), (int) Math.floor(startZ));
        // Position (r sin b, r cos b) => travel (cos b, -sin b); yaw = -atan2(dx, dz) = -b - 90. b - 90 matched only at
        // b = 180 (one-sector courses): lap scenes started 60 off the line and drove into the outer bank.
        float heading = (float) (-Math.toDegrees(start) - 90);
        return new Built(spec, centre, baseY, new Vec3(startX + 0.5, startY, startZ + 0.5), heading);
    }

    private static int height(Surface surface, double along, double across, int x, int z) {
        return switch (surface) {
            case FLAT -> 0;
            case WASHBOARD -> (int) Math.round(1.4 * Math.sin(along / 2.2));
            case RUTS -> Math.floorMod((int) Math.floor(across), 4) < 2 ? 1 : 0;
            case ROUGH -> noise(x, z);
            case STEPS -> {
                int band = Math.floorMod((int) (along / 9), 4);
                yield band == 1 ? 1 : band == 2 ? 2 : 0;
            }
            case CREST -> {
                double phase = Math.floorMod((int) along, 60) - 30.0;
                yield (int) Math.round(Math.max(0, 5 - Math.abs(phase) * 0.22));
            }
        };
    }

    /** Deterministic hash, not RNG: same "rough" every run, for comparable laps. */
    private static int noise(int x, int z) {
        int hash = x * 374761393 + z * 668265263;
        hash = (hash ^ (hash >>> 13)) * 1274126177;
        return Math.floorMod(hash ^ (hash >>> 16), 3) - 1;
    }

    private static void put(LevelChunk chunk, int x, int y, int z, BlockState state) {
        int index = chunk.getSectionIndex(y);
        if (index < 0 || index >= chunk.getSections().length) {
            return;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.getBlockState(x & 15, y & 15, z & 15) != state) {
            section.setBlockState(x & 15, y & 15, z & 15, state, false);
        }
    }

    /** Section writes bypass the {@code LevelChunk.setBlockState} mixin that invalidates
     * {@link ChunkCollisionCache}: must invalidate manually, or a second course cut leaves vehicles
     * colliding with the first course's blocks. Measured: 2nd-session lap sank trailer 2 blocks into
     * floor against the stops, fighting terrain that was no longer there. */
    private static void settle(ServerLevel level, LevelChunk chunk) {
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));
        ChunkCollisionCache.invalidateChunk(level, chunk.getPos());
        chunk.setUnsaved(true);
    }

    public static int groundAt(ServerLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(x), (int) Math.floor(z));
    }

    /** Hull-to-ground gap at the axle point; negative = hull inside ground. Possible because the
     * tow constraint runs after the contact solve, so a coupling can force a pose the ground
     * wouldn't otherwise allow. */
    public static double clearance(ServerLevel level, com.wf.combinedassault.entity.vehicle.AbstractVehicle vehicle) {
        Vec3 axle = vehicle.towing.towable.axle;
        Vec3 at = com.wf.combinedassault.tow.TowLinks.worldPoint(vehicle, new Vec3(axle.x, 0, axle.z));
        return at.y - groundAt(level, at.x, at.z);
    }

    public static BlockPos startPos(Built built) {
        return BlockPos.containing(built.start());
    }

}
