package com.wf.combinedassault.naval;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * Surface + seabed under one column, from heightmaps: 2 heightmap reads + 1 fluid state, vs thousands
 * for a hull-sized block scan. {@code MOTION_BLOCKING} counts fluid => surface; {@code OCEAN_FLOOR}
 * doesn't => bed. Top not water (bridge/pier/hull) => scan near the hint for it.
 */
public final class WaterColumn {

    /** Blocks scanned either side of the hint when the heightmap top is not a fluid. */
    private static final int SCAN = 12;

    public double surfaceY = Double.NaN;
    public double bedY = Double.NaN;
    /** Whether the last sample fell in a chunk that is not loaded, which says nothing about water. */
    public boolean unloaded;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** @return water found under the point; sets {@link #surfaceY}/{@link #bedY} iff true. */
    public boolean sample(Level level, double x, double z, double yHint) {
        surfaceY = Double.NaN;
        bedY = Double.NaN;
        unloaded = false;
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        ChunkAccess chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(bx),
                SectionPos.blockToSectionCoord(bz));
        if (chunk == null) {
            unloaded = true;
            return false;
        }
        // ChunkAccess.getHeight = topmost counted block, not first free (already -1 vs getFirstAvailable).
        // Bed a keel clears = face above topmost solid.
        int topBlock = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        int bed = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, bx, bz) + 1;
        FluidState fluid = chunk.getFluidState(cursor.set(bx, topBlock, bz));
        if (!fluid.isEmpty()) {
            surfaceY = topBlock + fluid.getOwnHeight();
            bedY = bed;
            return true;
        }
        // Solid top: scan near the hint for water under it.
        int from = Mth.floor(yHint) + SCAN;
        int to = Mth.floor(yHint) - SCAN;
        int minY = level.getMinBuildHeight();
        for (int y = Math.min(from, topBlock); y >= Math.max(to, minY); y--) {
            fluid = chunk.getFluidState(cursor.set(bx, y, bz));
            if (fluid.isEmpty()) {
                continue;
            }
            int surfaceBlock = y;
            for (int up = y + 1; up <= topBlock; up++) {
                if (chunk.getFluidState(cursor.set(bx, up, bz)).isEmpty()) {
                    break;
                }
                surfaceBlock = up;
            }
            FluidState surfaceState = chunk.getFluidState(cursor.set(bx, surfaceBlock, bz));
            surfaceY = surfaceBlock + surfaceState.getOwnHeight();
            int floor = surfaceBlock;
            while (floor > minY && !chunk.getFluidState(cursor.set(bx, floor - 1, bz)).isEmpty()) {
                floor--;
            }
            bedY = floor;
            return true;
        }
        return false;
    }

}
