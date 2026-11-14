package com.wf.combinedassault.radar;

import com.wf.combinedassault.radar.message.ServerRadarScope;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.recon.api.ReconNet;
import com.wf.wflib.recon.api.ScopeFrame;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Network picture for riders with the expanded scope open: sent only while a powered radar of the vehicle receives
 * ({@link com.wf.combinedassault.vehicle.pojo.ReconRole#receives}); silence => client shows no link.
 */
public final class RadarScopeFeed {
    private static final int PERIOD_TICKS = 10;
    private static final double MIN_RANGE = 250;
    private static final double MAX_RANGE = 16000;
    private static final Map<UUID, Double> SUBSCRIBED = new HashMap<>();

    private RadarScopeFeed() {
    }

    /** {@code range} <= 0 => stop. */
    public static void subscribe(ServerPlayer player, double range) {
        if (range <= 0) {
            SUBSCRIBED.remove(player.getUUID());
        } else {
            SUBSCRIBED.put(player.getUUID(), Math.max(MIN_RANGE, Math.min(MAX_RANGE, range)));
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (SUBSCRIBED.isEmpty() || event.getServer().getTickCount() % PERIOD_TICKS != 0) {
            return;
        }
        var it = SUBSCRIBED.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.getVehicle() instanceof AbstractVehicle vehicle) {
                send(player, vehicle, entry.getValue());
            }
        }
    }

    private static void send(ServerPlayer player, AbstractVehicle vehicle, double range) {
        if (!(vehicle.level() instanceof ServerLevel level) || !vehicle.hasPower()) {
            return;
        }
        RadarUnit receiver = receiver(vehicle);
        if (receiver == null) {
            return;
        }
        long netId = ReconBridge.netFor(level, vehicle, receiver);
        if (netId == ReconNet.UNAFFILIATED) {
            return;
        }
        List<com.wf.wflib.recon.api.SeismicFix> events = new ArrayList<>(ReconNet.events(level, netId));
        Collections.reverse(events);
        ScopeFrame frame = ScopeFrame.of(netId, vehicle.getX(), vehicle.getY(), vehicle.getZ(), range,
                ReconNet.picture(level, netId), events, level.getGameTime());
        PacketDistributor.sendToPlayer(player, new ServerRadarScope(frame));
    }

    private static RadarUnit receiver(AbstractVehicle vehicle) {
        List<PartUnit<?>> parts = vehicle.getPartUnits();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof WeaponUnit weapon) {
                for (RadarUnit radar : weapon.getRadarUnits()) {
                    if (radar.isOn() && radar.getReconRole().receives()) {
                        return radar;
                    }
                }
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SUBSCRIBED.remove(event.getEntity().getUUID());
    }
}
