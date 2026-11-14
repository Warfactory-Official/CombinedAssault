package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.damage.api.StrikeContext;
import com.wf.wflib.damage.api.Threat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Only entry point stock damage calls into; see {@link com.wf.combinedassault.ballistics.DamageSystem}.
 * Nothing else in the mod references this package.
 */
public final class Ballistics {

    private static BallisticResolver resolver = new ArmorResolver();

    private Ballistics() {
    }

    /** For stock-vs-armour model comparison on one shot. */
    public static void setResolver(BallisticResolver newResolver) {
        resolver = newResolver == null ? new ArmorResolver() : newResolver;
    }

    /** @param lead the ERA / slat panels in front of the hull: layers of the walk; null = none */
    @Nullable
    public static HullResult resolve(AbstractVehicle vehicle, DamageSource damageSource, float amount,
                                          @Nullable Vec3 hitPos, @Nullable AddonStrike.Lead lead) {
        if (hitPos == null) {
            return null;
        }
        StrikeContext round = StrikeContext.of(damageSource);
        if (round == null || round.threat().penetrationMm() <= 0) {
            return null;
        }
        Vec3 direction = round.velocity();
        if (direction.lengthSqr() == 0) {
            return null;
        }
        if (lead != null && lead.dead()) {
            return null;
        }
        return resolver.resolve(vehicle, round.position(), direction, hitPos, round.threat(), amount, lead);
    }

    /**
     * A blast ray, fragment or warhead jet from {@code start} along {@code direction} to the hull at {@code hitPos}: the
     * model's answer with {@code incomingDamage} priced.
     */
    @Nullable
    public static HullResult blast(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos, Threat threat,
                                   float incomingDamage, @Nullable AddonStrike.Lead lead) {
        return resolver.resolve(vehicle, start, direction, hitPos, threat, incomingDamage, lead);
    }

    /** Kinetic strike before damage: the model's whole answer, no damage priced ({@code HullResult#hullDamage} = 0). */
    @Nullable
    public static HullResult strike(AbstractVehicle vehicle, Vec3 start, Vec3 velocity, Vec3 hitPos, Threat threat,
                                    @Nullable AddonStrike.Lead lead) {
        if (threat.penetrationMm() <= 0 || velocity.lengthSqr() == 0 || lead != null && lead.dead()) {
            return null;
        }
        return resolver.resolve(vehicle, start, velocity, hitPos, threat, 0, lead);
    }

}
