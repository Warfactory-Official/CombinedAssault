package com.wf.combinedassault.radar;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.radar.message.ServerBroadcastEntities;
import com.wf.combinedassault.vehicle.entity.RemoteTickEntity;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.missile.api.SeekerDecoy;

/** Beyond-render-range entities sent to players riding a vehicle, for radar. */
public final class RadarBroadcast {

    private RadarBroadcast() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % RadarConfig.get().broadcastInterval() != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> entities = new ArrayList<>();
            for (Entity target : level.getEntities().getAll()) {
                if (!target.isAlive()) {
                    continue;
                }
                if ((target instanceof AbstractVehicle vehicle && !vehicle.isDestroyed())
                        || target instanceof MissileEntity
                        || target instanceof SeekerDecoy
                        || target instanceof RadarMarkerEntity
                        || RadarConfig.broadcastWhitelist().stream()
                        .anyMatch(entityType -> BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().matches(entityType))) {
                    entities.add(target);
                }
            }
            if (entities.isEmpty()) {
                continue;
            }
            ServerBroadcastEntities message = new ServerBroadcastEntities();
            message.entities = new ArrayList<>();
            for (Entity target : entities) {
                CompoundTag data = new CompoundTag();
                if (target instanceof RemoteTickEntity remoteTick) {
                    remoteTick.writeData(data);
                }
                message.entities.add(new ServerBroadcastEntities.BroadcastEntity(target.getId(),
                        EntityType.getKey(target.getType()), target.position(), target.getDeltaMovement(), data));
            }
            for (ServerPlayer player : level.players()) {
                if (player.getVehicle() instanceof AbstractVehicle) {
                    PacketDistributor.sendToPlayer(player, message);
                }
            }
        }
    }
}
