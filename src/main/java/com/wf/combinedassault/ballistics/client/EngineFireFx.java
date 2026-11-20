package com.wf.combinedassault.ballistics.client;

import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Flames and smoke on a live vehicle whose engine pool is BROKEN; visual only. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class EngineFireFx {
    private static final double RANGE_SQR = 96.0 * 96.0;

    private EngineFireFx() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle && !vehicle.isDestroyed()
                    && vehicle.distanceToSqr(mc.player) < RANGE_SQR) {
                burn(level, vehicle);
            }
        }
    }

    private static void burn(ClientLevel level, AbstractVehicle vehicle) {
        HullComponents pools = vehicle.hullComponents();
        for (int i = 0; i < pools.size(); i++) {
            if (pools.kind(i) != ComponentKind.ENGINE || pools.state(i) != ComponentState.BROKEN) {
                continue;
            }
            Vec3 at = anchor(vehicle);
            for (int n = 0; n < 6; n++) {
                level.addParticle(ParticleTypes.FLAME, at.x + spread(level), at.y + level.random.nextDouble() * 0.5,
                        at.z + spread(level), 0, 0.06, 0);
            }
            level.addParticle(ParticleTypes.LARGE_SMOKE, true, at.x + spread(level), at.y + 0.4, at.z + spread(level),
                    0, 0.06, 0);
            if (level.random.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.LAVA, at.x + spread(level), at.y + 0.3, at.z + spread(level), 0, 0, 0);
            }
            if (level.random.nextInt(4) == 0) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, at.x, at.y + 0.5, at.z, 0, 0.08, 0);
            }
            return;
        }
    }

    private static Vec3 anchor(AbstractVehicle vehicle) {
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            if (cell.kind() == ComponentKind.ENGINE) {
                for (VehicleSystems.Member member : cell.members()) {
                    Vec3 at = member.anchor(vehicle);
                    if (at != null) {
                        return at;
                    }
                }
            }
        }
        return vehicle.getBoundingBox().getCenter().add(0, vehicle.getBbHeight() * 0.3, 0);
    }

    private static double spread(ClientLevel level) {
        return (level.random.nextDouble() - 0.5) * 0.6;
    }
}
