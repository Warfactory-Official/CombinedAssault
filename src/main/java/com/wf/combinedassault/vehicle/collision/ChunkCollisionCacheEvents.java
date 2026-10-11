package com.wf.combinedassault.vehicle.collision;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Per-block invalidation: LevelChunk.setBlockState mixin, not here. */
@EventBusSubscriber
public final class ChunkCollisionCacheEvents {

    private ChunkCollisionCacheEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // Reloaded chunk reuses section keys => stale snapshot without this.
        if (event.getLevel() instanceof Level level) {
            ChunkCollisionCache.invalidateChunk(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            ChunkCollisionCache.invalidateChunk(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            ChunkCollisionCache.forget(level);
            // Level lists holding vehicles keep them alive => also forget here.
            com.wf.combinedassault.vehicle.parenting.CarrierDecks.forget(level);
            com.wf.combinedassault.vehicle.OversizedVehicles.forget(level);
        }
    }

}
