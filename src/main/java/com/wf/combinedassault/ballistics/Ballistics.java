package com.wf.combinedassault.ballistics;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.damage.api.StrikeContext;
import com.wf.wflib.damage.api.ThreatKind;
import com.wf.wflib.armor.api.hull.HullOutcome;
import com.wf.wflib.armor.api.hull.HullResult;

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

    /** @param penetrationScale what ERA/slat left of the round's penetration */
    @Nullable
    public static HullResult resolve(AbstractVehicle vehicle, DamageSource damageSource, float amount,
                                          @Nullable Vec3 hitPos, float penetrationScale) {
        if (hitPos == null) {
            return null;
        }
        StrikeContext round = StrikeContext.of(damageSource);
        if (round == null || round.threat().penetrationMm() <= 0) {
            return null;
        }
        float penetration = round.threat().penetrationMm() * penetrationScale;
        Vec3 direction = round.velocity();
        if (direction.lengthSqr() == 0) {
            return null;
        }
        return resolver.resolve(vehicle, round.position(), direction, hitPos, penetration, amount,
                round.threat().kind());
    }

    /** Kinetic strike before damage: struck face's outward normal when the model answers RICOCHET, else null. */
    @Nullable
    public static Vec3 ricochet(AbstractVehicle vehicle, Vec3 start, Vec3 velocity, Vec3 hitPos, float penetration) {
        if (penetration <= 0 || velocity.lengthSqr() == 0) {
            return null;
        }
        HullResult r = resolver.resolve(vehicle, start, velocity, hitPos, penetration, 0, ThreatKind.KINETIC);
        return r != null && r.outcome() == HullOutcome.RICOCHET ? r.normal() : null;
    }

}
