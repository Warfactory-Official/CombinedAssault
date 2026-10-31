package com.wf.combinedassault.ballistics;

import java.util.Iterator;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.message.ServerHitVehicleEvent;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.mixin.ballistics.ExplosionAccessor;
import com.wf.wflib.explosion.api.ExplosionAEF;

final class BallisticsEvents {

    private BallisticsEvents() {
    }

    @SubscribeEvent
    static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Iterator<Entity> iterator = event.getAffectedEntities().iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            iterator.remove();
            Explosion explosion = event.getExplosion();
            Vec3 explosionPos = explosion.center();
            float explosionRadius = ((ExplosionAccessor) explosion).getRadius() * 2.0F;
            if (entity.ignoreExplosion(explosion)) {
                continue;
            }
            ExplosionAEF aef = ExplosionAEF.running(explosion);
            float exposure = AddonStrike.blast(vehicle, explosionPos, ((ExplosionAccessor) explosion).getRadius(), aef);
            // Hull distance, not origin: a carrier's plate can be 30 blocks from its own spawn point.
            Vec3 hullPoint = VehicleBlast.nearestHullPoint(vehicle, explosionPos);
            if (aef != null && !aef.inBlastShape(hullPoint) && !aef.inBlastShape(vehicle.getBoundingBox().getCenter())) {
                continue;
            }
            double distance = hullPoint.distanceTo(explosionPos);
            double distanceRatio = distance / explosionRadius;
            if (distanceRatio > 1.0D) {
                continue;
            }
            double visibilityFactor = VehicleBlast.seenFraction(entity.level(), vehicle, explosionPos, hullPoint);
            double impactStrength = (1.0D - distanceRatio) * visibilityFactor * exposure;
            float damage = (float) ((int) ((impactStrength * impactStrength + impactStrength) / 2.0D * 7.0D * explosionRadius + 1.0D));
            Entity direct = explosion.getDirectSourceEntity();
            Entity causing = explosion.getIndirectSourceEntity();
            entity.hurt(VehicleDamageTypes.Sources.explosion(entity.level().registryAccess(), direct, causing, hullPoint), damage);
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
