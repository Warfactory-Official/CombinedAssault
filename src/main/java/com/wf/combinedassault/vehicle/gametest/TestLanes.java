package com.wf.combinedassault.vehicle.gametest;

import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A vehicle on a stone floor far from the 3x3 arena grid: arenas share one world, and a vehicle (or what it fires)
 * overhanging its arena reaches the neighbours. {@code laneZ < 0}: other benches offset per-arena origins at z > 0.
 */
public final class TestLanes {

    /** Ticks after spawning before the body runs. */
    private static final int SETTLE = 40;

    /** First lane's arena origin; per-arena origins => distinct {@code laneZ} can collide. */
    private static BlockPos origin;

    /** Live z spans (lane space) -> {to, expiry tick}. Failed body never releases => expiry. */
    private static final Map<Integer, long[]> CLAIMS = new HashMap<>();
    /** > longest lane-test timeout (1600). */
    private static final int CLAIM_TTL = 2000;

    @FunctionalInterface
    public interface Body {
        GameTestSequence run(GameTestSequence seq, Supplier<AbstractVehicle> vehicle);
    }

    private TestLanes() {
    }

    /**
     * Floor {@code (2 floorX + 1) x (2 floorZ + 1)} at run origin + {@code (0, 0, laneZ)}, chunks forced; vehicle
     * spawned once entity-ticking. After {@code body}: vehicle discarded, {@code teardown(origin)}, floor and chunks
     * released, success. A failing body skips all of it.
     */
    public static void run(GameTestHelper helper, int laneZ, int floorX, int floorZ, ResourceLocation id, Body body,
                           Consumer<BlockPos> teardown) {
        ServerLevel level = helper.getLevel();
        // Chunk centre: only at's chunk is waited for => a body within 8 blocks of at ticks.
        // TODO: neighbour forced chunks sometimes never reach entity ticking within 300 ticks (no queued chunk task, idle workers).
        // Floor span only: forced-chunk margins may overlap (ForcedChunks counts holds).
        int from = laneZ - floorZ, to = laneZ + floorZ;
        claim(helper, from, to);
        BlockPos lane = laneOrigin(helper).offset(0, 0, laneZ);
        BlockPos at = new BlockPos((lane.getX() & ~15) | 8, lane.getY(), (lane.getZ() & ~15) | 8);
        force(level, at, true);
        floor(level, at, floorX, floorZ, Blocks.STONE.defaultBlockState());
        AbstractVehicle[] spawned = new AbstractVehicle[1];
        GameTestSequence seq = helper.startSequence()
                .thenWaitUntil(ForcedChunks.ticking(level, List.of(new ChunkPos(at))))
                .thenExecute(() -> spawned[0] = spawn(helper, id, Vec3.atBottomCenterOf(at.above())))
                .thenIdle(SETTLE);
        body.run(seq, () -> spawned[0]).thenExecute(() -> {
            spawned[0].discard();
            teardown.accept(at);
            floor(level, at, floorX, floorZ, Blocks.AIR.defaultBlockState());
            force(level, at, false);
            release(from);
        }).thenSucceed();
    }

    /**
     * Claims lane z span {@code [from, to]} for a running test; overlapping a live claim throws (two tests on one
     * floor: shared blocks, one test's effects on the other).
     */
    public static void claim(GameTestHelper helper, int from, int to) {
        long now = helper.getLevel().getGameTime();
        CLAIMS.values().removeIf(c -> c[1] < now);
        for (Map.Entry<Integer, long[]> c : CLAIMS.entrySet()) {
            if (from <= c.getValue()[0] && c.getKey() <= to) {
                throw new IllegalStateException("lane span " + from + ".." + to + " overlaps live span " + c.getKey()
                        + ".." + c.getValue()[0]);
            }
        }
        CLAIMS.put(from, new long[]{to, now + CLAIM_TTL});
    }

    public static void release(int from) {
        CLAIMS.remove(from);
    }

    /** Shared lane origin for every lane user, so distinct {@code laneZ} stay apart. */
    public static BlockPos laneOrigin(GameTestHelper helper) {
        if (origin == null) {
            origin = helper.absolutePos(BlockPos.ZERO);
        }
        return origin;
    }

    public static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id, Vec3 at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        helper.assertTrue(data.isPresent(), id + " is not in the roster");
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, 0);
        vehicle.setDisplayId(id);
        helper.assertTrue(helper.getLevel().addFreshEntity(vehicle), id + " could not be added");
        return vehicle;
    }

    /** Failure text for a foreign lock: entity type + offset from {@code from}, or "itself". */
    public static String describe(GameTestHelper helper, @Nullable UUID id, Entity from) {
        Entity e = id == null ? null : helper.getLevel().getEntity(id);
        return e == from ? "itself" : e == null ? id + " (gone)" : e.getType().toShortString() + " "
                + e.getStringUUID() + " (from " + from.getStringUUID() + ") at "
                + e.position().subtract(from.position());
    }

    private static void floor(ServerLevel level, BlockPos at, int floorX, int floorZ,
                              net.minecraft.world.level.block.state.BlockState state) {
        for (int x = -floorX; x <= floorX; x++) {
            for (int z = -floorZ; z <= floorZ; z++) {
                level.setBlockAndUpdate(at.offset(x, 0, z), state);
            }
        }
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = (at.getX() - 16) >> 4; cx <= (at.getX() + 16) >> 4; cx++) {
            for (int cz = (at.getZ() - 16) >> 4; cz <= (at.getZ() + 16) >> 4; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        ForcedChunks.set(level, chunks, forced);
    }
}
