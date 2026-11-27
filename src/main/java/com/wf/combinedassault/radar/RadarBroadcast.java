package com.wf.combinedassault.radar;

import com.wf.combinedassault.radar.message.ServerBroadcastEntities;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.RemoteTickEntity;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.missile.api.SeekerDecoy;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Beyond-render-range entities for a vehicle rider's client radar: those inside its own radars' detect volume
 * ({@code Radar.detectTargets}: range * rcs) plus {@link RadarAudience} grants.
 */
public final class RadarBroadcast {
    /** Client positions lag server's by up to one interval, both ends moving. */
    private static final double SLACK = 32;

    private RadarBroadcast() {
    }

    private record Contact(Entity entity, double rcs, double speed, ServerBroadcastEntities.BroadcastEntity row) {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int interval = RadarConfig.get().broadcastInterval();
        if (server.getTickCount() % interval != 0) {
            return;
        }
        long now = Util.getMillis();
        RadarAudience.prune(now);
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> riders = new ArrayList<>();
            for (ServerPlayer player : level.players()) {
                if (player.getVehicle() instanceof AbstractVehicle) {
                    riders.add(player);
                }
            }
            if (riders.isEmpty()) {
                continue;
            }
            List<Contact> contacts = contacts(level);
            for (ServerPlayer player : riders) {
                AbstractVehicle vehicle = (AbstractVehicle) player.getVehicle();
                double range = radarRange(vehicle);
                double own = vehicle.getDeltaMovement().length();
                ServerBroadcastEntities message = new ServerBroadcastEntities();
                message.entities = new ArrayList<>();
                for (Contact c : contacts) {
                    double reach = range * c.rcs + (own + c.speed) * interval + SLACK;
                    if (c.entity != vehicle && (range > 0 && c.entity.distanceToSqr(vehicle) <= reach * reach
                            || RadarAudience.granted(player, c.entity.getId(), now))) {
                        message.entities.add(c.row);
                    }
                }
                if (!message.entities.isEmpty()) {
                    PacketDistributor.sendToPlayer(player, message);
                }
            }
        }
    }

    private static List<Contact> contacts(ServerLevel level) {
        List<Contact> contacts = new ArrayList<>();
        for (Entity target : level.getEntities().getAll()) {
            if (!target.isAlive()) {
                continue;
            }
            double rcs = 1;
            if (target instanceof AbstractVehicle vehicle) {
                if (vehicle.isDestroyed()) {
                    continue;
                }
                rcs = Math.max(1, vehicle.physicsEngine.radarCrossSection);
            } else if (!(target instanceof MissileEntity || target instanceof SeekerDecoy
                    || target instanceof RadarMarkerEntity || RadarConfig.broadcasts(target.getType()))) {
                continue;
            }
            CompoundTag data = new CompoundTag();
            if (target instanceof RemoteTickEntity remoteTick) {
                remoteTick.writeData(data);
            }
            contacts.add(new Contact(target, rcs, target.getDeltaMovement().length(),
                    new ServerBroadcastEntities.BroadcastEntity(target.getId(), EntityType.getKey(target.getType()),
                            target.position(), target.getDeltaMovement(), data)));
        }
        return contacts;
    }

    private static double radarRange(AbstractVehicle vehicle) {
        double range = 0;
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (unit instanceof RadarUnit radar) {
                range = Math.max(range, radar.getMaxScanDistance());
            }
        }
        return range;
    }
}
