package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.message.ServerVehicleExplosion;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.core.api.ViewRange;
import com.wf.wflib.explosion.api.BlastPayload;
import com.wf.wflib.explosion.api.EntityProcessorCross;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.explosion.api.PlayerProcessorStandard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** A vehicle's own death blast: wflib AEF entity stage, no blocks; CA client effect. */
public final class VehicleExplosion {

    private VehicleExplosion() {
    }

    /** Fuel and ammunition cook-off: a charge of {@code 2 x radius} blast size, fireball to the same reach. TODO estimate. */
    static BlastPayload payload(float radius) {
        return BlastPayload.ofSize(2 * radius).withThermal(2 * radius);
    }

    /** Radius = half the main box's length. The wreck is the exploder => spared. Vehicles in reach resolve {@link #payload}. */
    public static void detonate(AbstractVehicle wreck, @Nullable Entity killer) {
        Vec3 at = wreck.position();
        float radius = wreck.mainBox().extents().z;
        DamageSource source = VehicleDamageTypes.Sources.explosion(wreck.level().registryAccess(), wreck, killer, at);
        new ExplosionAEF(wreck.level(), at.x, at.y, at.z, radius, wreck)
                .payload(payload(radius))
                .setEntityProcessor(new EntityProcessorCross() {
                    @Override
                    public void attackEntity(Entity entity, ExplosionAEF explosion, float amount, Vec3 aim) {
                        entity.hurt(source, amount);
                    }
                })
                .setPlayerProcessor(new PlayerProcessorStandard())
                .setSFX((explosion, level, x, y, z, size) -> {
                    ServerVehicleExplosion packet = new ServerVehicleExplosion(killer == null ? -1 : killer.getId(), x, y, z, size);
                    double range = ViewRange.blocks();
                    for (ServerPlayer player : ((ServerLevel) level).getPlayers(p -> p.distanceToSqr(x, y, z) < range * range)) {
                        PacketDistributor.sendToPlayer(player, packet);
                    }
                })
                .explode();
    }
}
