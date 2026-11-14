package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.message.ServerHitVehicleEvent;
import com.wf.combinedassault.mixin.ballistics.ExplosionAccessor;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.explosion.api.BlastPayload;
import com.wf.wflib.explosion.api.ExplosionAEF;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Iterator;
import java.util.List;

final class BallisticsEvents {

    private BallisticsEvents() {
    }

    /** Vanilla explosions only: an AEF reaches vehicles as {@code BlastTarget}. Payload synthesised from the radius. */
    @SubscribeEvent
    static void onVanillaExplosion(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        if (ExplosionAEF.running(explosion) != null) {
            return;
        }
        Iterator<Entity> iterator = event.getAffectedEntities().iterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof AbstractVehicle vehicle) {
                iterator.remove();
                VehicleBlastResolver.resolve(vehicle, explosion, null,
                        BlastPayload.ofSize(((ExplosionAccessor) explosion).getRadius()), explosion.center());
            }
        }
    }

    @SubscribeEvent
    static void onHitVehicle(HitVehicleEvent event) {
        ServerPlayer serverPlayer = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(event.shooterUuid);
        if (serverPlayer == null) {
            return;
        }
        Level level = serverPlayer.level();
        if (level.getEntity(event.entityId) instanceof AbstractVehicle vehicle) {
            List<Entity> players = level.getEntities(vehicle,
                    AABB.ofSize(vehicle.position(), 256, 256, 256),
                    entity -> entity instanceof ServerPlayer && entity != serverPlayer);
            players.add(serverPlayer);
            players.forEach(player -> PacketDistributor.sendToPlayer((ServerPlayer) player, new ServerHitVehicleEvent(event)));
        }
    }

}
