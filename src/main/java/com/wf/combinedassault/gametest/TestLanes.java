package com.wf.combinedassault.gametest;

import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
    /** Lanes < 48 apart share chunks; {@link ForcedChunks} is not refcounted. */
    private static final Map<Long, Integer> FORCED = new HashMap<>();

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
        if (origin == null) {
            origin = helper.absolutePos(BlockPos.ZERO);
        }
        BlockPos at = origin.offset(0, 0, laneZ);
        force(level, at, true);
        floor(level, at, floorX, floorZ, Blocks.STONE.defaultBlockState());
        AbstractVehicle[] spawned = new AbstractVehicle[1];
        GameTestSequence seq = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(at), "lane not entity-ticking yet"))
                .thenExecute(() -> spawned[0] = spawn(helper, id, Vec3.atBottomCenterOf(at.above())))
                .thenIdle(SETTLE);
        body.run(seq, () -> spawned[0]).thenExecute(() -> {
            spawned[0].discard();
            teardown.accept(at);
            floor(level, at, floorX, floorZ, Blocks.AIR.defaultBlockState());
            force(level, at, false);
        }).thenSucceed();
    }

    public static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id, Vec3 at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        helper.assertTrue(data.isPresent(), id + " is not in the roster");
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, 0);
        vehicle.setDisplayId(id);
        helper.assertTrue(helper.getLevel().addFreshEntity(vehicle), id + " could not be added");
        return vehicle;
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
        for (int cx = (at.getX() - 16) >> 4; cx <= (at.getX() + 16) >> 4; cx++) {
            for (int cz = (at.getZ() - 16) >> 4; cz <= (at.getZ() + 16) >> 4; cz++) {
                int n = FORCED.merge(ChunkPos.asLong(cx, cz), forced ? 1 : -1, Integer::sum);
                if (forced ? n == 1 : n == 0) {
                    ForcedChunks.set(level, cx, cz, forced);
                }
            }
        }
    }
}
