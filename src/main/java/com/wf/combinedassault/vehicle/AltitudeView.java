package com.wf.combinedassault.vehicle;

import com.wf.combinedassault.all.AllConfigs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.ModList;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chunk view by height {@code h} over terrain: slant reach of the ground view kept, {@code r = sqrt((16 v)^2 - h^2) / 16},
 * floor {@code altitudeViewFloor}; Boxy LOD draws beyond. Shrinks only by >= 2: altitude wobble => no ring churn.
 * Sending only: player tickets (loading) stay server-wide. Entity tracking shrinks with it: entities past the shrunk view
 * reach the client only through Boxy's entity extension ({@code trackedEntityTypes}).
 */
public final class AltitudeView {

    private static final boolean LOD = ModList.get().isLoaded("boxy");
    private static final Map<ServerPlayer, State> STATES = new WeakHashMap<>();

    private record State(long tick, int input, int view) {}

    private AltitudeView() {
    }

    public static int view(ServerPlayer player, int view) {
        int floor = AllConfigs.server.altitudeViewFloor.get();
        if (!LOD || floor <= 0 || floor >= view) {
            return view;
        }
        long tick = player.server.getTickCount();
        State last = STATES.get(player);
        if (last != null && last.tick == tick && last.input == view) {
            return last.view;
        }
        double h = player.getY() - player.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ());
        double reach = view * 16.0;
        int want = h <= 0 ? view : Mth.clamp(Mth.ceil(Math.sqrt(Math.max(0, reach * reach - h * h)) / 16.0), floor, view);
        if (last != null && want < last.view && last.view - want < 2 && last.view <= view) {
            want = last.view;
        }
        STATES.put(player, new State(tick, view, want));
        return want;
    }
}
