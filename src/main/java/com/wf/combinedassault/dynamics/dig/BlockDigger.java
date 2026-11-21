package com.wf.combinedassault.dynamics.dig;

import com.wf.combinedassault.dynamics.PhysicsEngine;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.compat.api.TerritoryVerdict;
import com.wf.wflib.compat.api.WarforgeCompat;
import com.wf.wflib.geom.api.OBB;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Mines the cells a {@link DigSpec} zone overlaps: vanilla destroy-progress per cell, cracks shown, claims asked per cell.
 * The zone never collides; the hull does, so a vehicle waits against a block until it is gone.
 * Tick thread, one instance per tool.
 */
public final class BlockDigger {

    /** Cell centre may sit this far outside the zone and still count. */
    private static final float CELL_REACH = 0.35f;
    /** Progress lost per tick by a cell the zone no longer touches. */
    private static final float DECAY = 0.05f;
    private static final float HARVESTED = 30f;
    private static final float UNHARVESTED = 100f;
    private static final int VERDICT_TTL = 20;

    private final AbstractVehicle vehicle;
    private final Long2FloatOpenHashMap progress = new Long2FloatOpenHashMap();
    private final Long2IntOpenHashMap stage = new Long2IntOpenHashMap();
    private final LongOpenHashSet touched = new LongOpenHashSet();
    private final Long2IntOpenHashMap verdictTick = new Long2IntOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap verdictAllowed =
            new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Quaternionf inverse = new Quaternionf();
    private final Vector3f local = new Vector3f();
    private final LongArrayList done = new LongArrayList();

    public BlockDigger(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
        progress.defaultReturnValue(0f);
        stage.defaultReturnValue(-1);
    }

    /** One tick of mining by {@code zone} as {@code operator} (null => outsider: claimed ground refuses). */
    public void dig(ServerLevel level, DigSpec spec, OBB zone, UUID operator) {
        touched.clear();
        AABB bounds = zone.bounds().inflate(CELL_REACH);
        Vector3f extents = zone.extents();
        zone.rotation().conjugate(inverse);
        double cx = zone.centerX(), cy = zone.centerY(), cz = zone.centerZ();
        UUID faction = operator == null ? null : WarforgeCompat.factionOfPlayer(operator);
        for (int y = Mth.floor(bounds.minY); y <= Mth.floor(bounds.maxY); y++) {
            for (int z = Mth.floor(bounds.minZ); z <= Mth.floor(bounds.maxZ); z++) {
                for (int x = Mth.floor(bounds.minX); x <= Mth.floor(bounds.maxX); x++) {
                    inverse.transform(local.set((float) (x + 0.5 - cx), (float) (y + 0.5 - cy), (float) (z + 0.5 - cz)));
                    if (Math.abs(local.x) > extents.x + CELL_REACH || Math.abs(local.y) > extents.y + CELL_REACH
                            || Math.abs(local.z) > extents.z + CELL_REACH) {
                        continue;
                    }
                    mine(level, spec, cursor.set(x, y, z), faction);
                }
            }
        }
        for (int i = 0; i < done.size(); i++) {
            release(level, done.getLong(i));
        }
        done.clear();
        decay(level);
    }

    /** Whether {@code spec} may mine {@code state} here at all: hardness, tier, claims. */
    public boolean mineable(ServerLevel level, DigSpec spec, BlockPos pos, BlockState state, UUID faction) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness >= PhysicsEngine.UNBREAKABLE_HARDNESS) {
            return false;
        }
        if (state.requiresCorrectToolForDrops() && state.is(spec.tier().getIncorrectBlocksForDrops())) {
            return false;
        }
        return claimAllows(level, pos, faction);
    }

    private void mine(ServerLevel level, DigSpec spec, BlockPos.MutableBlockPos pos, UUID faction) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
            return;
        }
        if (!mineable(level, spec, pos, state, faction)) {
            return;
        }
        long key = pos.asLong();
        touched.add(key);
        boolean tooled = spec.tool().mineable != null && state.is(spec.tool().mineable);
        boolean harvest = !state.requiresCorrectToolForDrops() || tooled;
        float hardness = state.getDestroySpeed(level, pos);
        float step = hardness == 0 ? 1f : (tooled ? spec.speed() : 1f) / hardness / (harvest ? HARVESTED : UNHARVESTED);
        float total = progress.get(key) + step;
        if (total >= 1f) {
            done.add(key);
            BlockPos at = pos.immutable();
            // Fresh verdict: the cached one may be 20 ticks old.
            if (WarforgeCompat.excavationVerdict(level, faction, at).allowed()) {
                BlockEntity entity = level.getBlockEntity(at);
                if (spec.drops() && harvest) {
                    Block.dropResources(state, level, at, entity, vehicle, spec.toolStack());
                }
                level.destroyBlock(at, false, vehicle);
            }
            return;
        }
        progress.put(key, total);
        int shown = Math.min(9, (int) (total * 10f));
        if (stage.get(key) != shown) {
            stage.put(key, shown);
            level.destroyBlockProgress(breaker(key), BlockPos.of(key), shown);
        }
    }

    private void decay(ServerLevel level) {
        if (progress.isEmpty()) {
            return;
        }
        LongArrayList gone = null;
        for (var it = progress.long2FloatEntrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            long key = entry.getLongKey();
            if (touched.contains(key)) {
                continue;
            }
            float left = entry.getFloatValue() - DECAY;
            if (left <= 0f) {
                if (gone == null) {
                    gone = new LongArrayList();
                }
                gone.add(key);
            } else {
                entry.setValue(left);
            }
        }
        if (gone != null) {
            for (int i = 0; i < gone.size(); i++) {
                release(level, gone.getLong(i));
            }
        }
    }

    private void release(ServerLevel level, long key) {
        progress.remove(key);
        if (stage.remove(key) >= 0) {
            level.destroyBlockProgress(breaker(key), BlockPos.of(key), -1);
        }
    }

    /** Stop mining: every crack cleared. */
    public void clear(ServerLevel level) {
        for (long key : progress.keySet().toLongArray()) {
            release(level, key);
        }
        verdictTick.clear();
        verdictAllowed.clear();
    }

    /** Distinct per cell so cracks of several cells show at once. */
    private int breaker(long key) {
        return vehicle.getId() * 31 + (int) (key ^ key >>> 32) * 0x9E3779B1;
    }

    private boolean claimAllows(ServerLevel level, BlockPos pos, UUID faction) {
        long chunk = ChunkPos.asLong(pos);
        int now = vehicle.tickCount;
        if (verdictTick.containsKey(chunk) && now - verdictTick.get(chunk) < VERDICT_TTL) {
            return verdictAllowed.get(chunk);
        }
        TerritoryVerdict verdict = WarforgeCompat.excavationVerdict(level, faction, pos);
        verdictTick.put(chunk, now);
        verdictAllowed.put(chunk, verdict.allowed());
        return verdict.allowed();
    }
}
