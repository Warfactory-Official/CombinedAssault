package com.wf.combinedassault.radar;

import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Entities a player's client resolves beyond its own radar volume: RWR emitters, own hit targets. */
public final class RadarAudience {
    /** >= client hit-widget window (3 s) and RWR hold (0.5 s). */
    private static final long KEEP_MS = 3000;
    /** Radar scan runs on {@code radarExecutor} => concurrent. */
    private static final Map<UUID, Map<Integer, Long>> GRANTED = new ConcurrentHashMap<>();

    private RadarAudience() {
    }

    public static void grant(ServerPlayer player, int entityId) {
        GRANTED.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(entityId, Util.getMillis() + KEEP_MS);
    }

    static boolean granted(ServerPlayer player, int entityId, long now) {
        Map<Integer, Long> ids = GRANTED.get(player.getUUID());
        return ids != null && ids.getOrDefault(entityId, 0L) >= now;
    }

    static void prune(long now) {
        GRANTED.values().forEach(ids -> ids.values().removeIf(until -> until < now));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        GRANTED.remove(event.getEntity().getUUID());
    }
}
