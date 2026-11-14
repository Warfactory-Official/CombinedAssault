package com.wf.combinedassault.ballistics.addon;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.EraType;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.HullResolver;
import com.wf.wflib.explosion.api.EntityProcessorCross;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.explosion.api.PlayerProcessorStandard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One brick going off: its {@code BlastPayload} to everything in reach, no blocks. */
final class EraBlast {

    /** Entity reach of a brick, blocks. */
    static final float RADIUS = 1.2f;
    /** No hull figure under the brick: counts as thick (no flyer plate). */
    private static final float UNKNOWN_BACKING = Float.POSITIVE_INFINITY;

    private EraBlast() {
    }

    /**
     * @param nominalKeMm RHAe of the plate under the brick ({@link #backing})
     */
    static void detonate(ServerLevel level, Vec3 at, EraType type, float nominalKeMm) {
        new ExplosionAEF(level, at.x, at.y, at.z, RADIUS)
                .payload(type.payload(nominalKeMm))
                .setEntityProcessor(new EntityProcessorCross())
                .setPlayerProcessor(new PlayerProcessorStandard())
                .setSFX((explosion, l, x, y, z, size) -> {
                    ((ServerLevel) l).sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
                    l.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.6f, 1.6f);
                })
                .explode();
    }

    /** KE RHAe of the hull plate {@code inward} (unit) of {@code at}: what a brick sits on. */
    static float backing(AbstractVehicle vehicle, Vec3 at, Vec3 inward) {
        HullProfile profile = vehicle.hullProfile();
        if (profile == null || !profile.isDefined()) {
            return UNKNOWN_BACKING;
        }
        HullResolver.Plate plate = HullResolver.plate(vehicle, profile, at.subtract(inward.scale(0.3)),
                at.add(inward.scale(1.5)), inward, false);
        return plate == null ? UNKNOWN_BACKING : plate.nominal();
    }

    /** KE RHAe of {@code plateId}'s own layers. */
    static float backing(@Nullable HullProfile profile, String plateId) {
        if (profile == null || profile.plates.get(plateId) == null) {
            return UNKNOWN_BACKING;
        }
        return profile.plates.get(plateId).nominal(false);
    }
}
