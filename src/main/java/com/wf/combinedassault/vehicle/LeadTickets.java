package com.wf.combinedassault.vehicle;

import com.wf.combinedassault.all.AllConfigs;
import com.wf.wflib.mixin.AccessorChunkMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Fast player (root entity: ridden vehicle, elytra): chunks the view disc will reach within {@code leadLoadTicks} are
 * loaded to FULL before the disc arrives; vanilla then sends them on entry. Covers view disc shifted by
 * {@code v * leadLoadTicks}, capped at one view radius, minus the own disc.
 * Sender idle at speed, readiness lags (`ca_chunkstream`, REDACTED): tickets only, no extra sends.
 */
public final class LeadTickets {

    /** FULL, not ticking: region ticket radius 0 = level 33. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create("combinedassault_lead",
            Comparator.comparingLong(ChunkPos::toLong), 60);
    private static final int REFRESH = 20;
    /** Blocks/tick. Below: vanilla keeps up. Above max: teleport, not motion. */
    private static final double MIN_SPEED = 1.0;
    private static final double MAX_SPEED = 64.0;

    private static final Map<Entity, Stamp> STAMPS = new IdentityHashMap<>();

    private record Stamp(long lead, long own, long tick) {
    }

    private LeadTickets() {
    }

    public static void tick(MinecraftServer server) {
        int leadTicks = AllConfigs.server.leadLoadTicks.get();
        long now = server.getTickCount();
        if (leadTicks <= 0) {
            STAMPS.clear();
            return;
        }
        STAMPS.entrySet().removeIf(e -> e.getKey().isRemoved() || now - e.getValue().tick > REFRESH);
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                stamp(level, player.getRootVehicle(), ((AccessorChunkMap) level.getChunkSource().chunkMap).wfPlayerViewDistance(player), leadTicks, now);
            }
        }
    }

    private static void stamp(ServerLevel level, Entity root, int view, int leadTicks, long now) {
        double vx = root.getX() - root.xo, vz = root.getZ() - root.zo;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            return;
        }
        double ahead = Math.min(speed * leadTicks, view * 16.0) / speed;
        ChunkPos own = root.chunkPosition();
        ChunkPos lead = new ChunkPos(SectionPos.blockToSectionCoord(root.getX() + vx * ahead),
                SectionPos.blockToSectionCoord(root.getZ() + vz * ahead));
        Stamp last = STAMPS.get(root);
        if (last != null && last.lead == lead.toLong() && last.own == own.toLong() && now - last.tick < REFRESH) {
            return;
        }
        STAMPS.put(root, new Stamp(lead.toLong(), own.toLong(), now));
        ChunkTrackingView mine = ChunkTrackingView.of(own, view);
        ChunkTrackingView.of(lead, view).forEach(pos -> {
            if (!mine.contains(pos)) {
                level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
            }
        });
    }
}
