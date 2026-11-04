package com.wf.combinedassault.ballistics.spall;

import com.norwood.ahf.part.HitboxPart;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.core.api.Seeded;
import com.wf.wflib.damage.api.BlastDamageSource;
import com.wf.wflib.damage.api.DamageClass;
import com.wf.wflib.damage.api.StrikeContext;
import com.wf.wflib.damage.api.WFDamageSources;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Behind-armour debris of a defeated plate (layer 3): a seeded fan of fragments from the exit point along the shot, to
 * occupants (wflib strike path: AHF part, WFMedical wound, personal armour) and to internal systems. Energy follows
 * the hull model's passed-through share, continuous in the penetration ratio; an absorbed or ricocheted hit throws none.
 */
public final class Spall {

    /** Fragments per penetration, upper bound. */
    public static final int MAX_FRAGMENTS = 12;
    /** Share of the struck-system damage still taken by the single struck system; fragments carry the rest. */
    public static final float DIRECT_SHARE = 0.5f;
    /** Crew damage per unit of passed-through damage. */
    static final float CREW_SHARE = 0.5f;
    /** Passed-through damage per fragment before the cap. */
    static final float FRAGMENT_UNIT = 4.0f;
    static final double RANGE = 8.0;
    static final double EXIT_OFFSET = 0.25;
    static final float KINETIC_CONE_DEG = 30.0f;
    static final float JET_CONE_DEG = 8.0f;
    static final float JET_HEAT = 1.5f;
    static final int JET_DIVISOR = 3;
    static final int BURN_SECONDS = 8;

    private static final ThreadLocal<Integer> DELIVERING = ThreadLocal.withInitial(() -> 0);

    private Spall() {
    }

    /** True while crew damage is being delivered on purpose: {@code protect_passenger} lets it through. */
    public static boolean delivering() {
        return DELIVERING.get() > 0;
    }

    public static void deliver(Runnable body) {
        DELIVERING.set(DELIVERING.get() + 1);
        try {
            body.run();
        } finally {
            DELIVERING.set(DELIVERING.get() - 1);
        }
    }

    /** Occupants bail out into the wreck's own blast ({@code VehicleExplosion}) already burning. */
    public static void onDestroyed(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            passenger.igniteForSeconds(BURN_SECONDS);
        }
    }

    /**
     * @param incoming     damage before armour
     * @param systemAmount what the struck system would take whole
     * @return fragments flew: the caller lowers the struck system's share to {@link #DIRECT_SHARE}
     */
    public static boolean onPenetration(AbstractVehicle vehicle, StrikeContext round, HullResult result, Vec3 hitPos,
                                        float incoming, float systemAmount) {
        if (!result.outcome().isThrough() || incoming <= 0 || round.velocity().lengthSqr() < 1.0e-12) {
            return false;
        }
        float passed = Math.min(1.0f, Math.max(0.0f, result.hullDamage() / incoming));
        float energy = incoming * passed;
        if (energy <= 0) {
            return false;
        }
        boolean jet = round.threat().kind().isShapedCharge();
        int count = Math.max(1, Math.min(MAX_FRAGMENTS, (int) Math.ceil(energy / FRAGMENT_UNIT)));
        if (jet) {
            count = Math.max(1, count / JET_DIVISOR);
        }
        float perCrew = energy / count * CREW_SHARE * (jet ? JET_HEAT : 1.0f);
        float perSystem = systemAmount * (1 - DIRECT_SHARE) / count;
        Vec3 dir = round.velocity().normalize();
        Vec3 exit = hitPos.add(dir.scale(EXIT_OFFSET));
        long seed = Seeded.mix(Math.round((hitPos.x - vehicle.getX()) * 1000)
                ^ Seeded.mix(Math.round((hitPos.y - vehicle.getY()) * 1000) ^ Seeded.mix(Math.round((hitPos.z - vehicle.getZ()) * 1000))));
        Vec3[] fan = fan(dir, jet ? JET_CONE_DEG : KINETIC_CONE_DEG, count, seed);
        strikeSystems(vehicle, exit, fan, perSystem);
        strikeCrew(vehicle, round, exit, fan, perCrew);
        return true;
    }

    static Vec3[] fan(Vec3 dir, float coneDeg, int count, long seed) {
        Vec3 right = Math.abs(dir.y) > 0.999 ? new Vec3(1, 0, 0) : dir.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(dir);
        double cone = Math.toRadians(coneDeg);
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        double turn = 2 * Math.PI * Seeded.unit(seed, 0, 0);
        Vec3[] out = new Vec3[count];
        for (int i = 0; i < count; i++) {
            double r = cone * Math.sqrt((i + 0.5 + 0.5 * Seeded.unit(seed, i, 1)) / count);
            double phi = turn + golden * i;
            Vec3 off = right.scale(Math.cos(phi)).add(up.scale(Math.sin(phi)));
            out[i] = dir.scale(Math.cos(r)).add(off.scale(Math.sin(r))).normalize();
        }
        return out;
    }

    private static void strikeSystems(AbstractVehicle vehicle, Vec3 exit, Vec3[] fan, float perSystem) {
        VehicleHits hits = vehicle.hits();
        HullComponents systems = vehicle.hullComponents();
        if (hits == null || systems.isEmpty() || perSystem <= 0) {
            return;
        }
        for (Vec3 d : fan) {
            VehicleHits.Hit module = hits.module(exit, exit.add(d.scale(RANGE)));
            if (module != null) {
                HitModel.Node node = hits.model().nodes().get(module.node());
                systems.hurt(node.systemId(), perSystem);
            }
        }
    }

    private static void strikeCrew(AbstractVehicle vehicle, StrikeContext round, Vec3 exit, Vec3[] fan, float perFragment) {
        if (perFragment <= 0) {
            return;
        }
        List<LivingEntity> occupants = new ArrayList<>();
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof LivingEntity living && living.isAlive()) {
                occupants.add(living);
            }
        }
        for (LivingEntity victim : occupants) {
            float damage = 0;
            Map<HitboxPart, Float> byPart = new EnumMap<>(HitboxPart.class);
            HitboxPart part = null;
            Vec3 through = null;
            float best = 0;
            for (Vec3 d : fan) {
                Rounds.LiveHit hit = Rounds.hitLive(victim, exit, exit.add(d.scale(RANGE)));
                if (hit == null) {
                    continue;
                }
                damage += perFragment;
                float onPart = hit.part() == null ? perFragment : byPart.merge(hit.part(), perFragment, Float::sum);
                if (through == null || onPart > best) {
                    best = onPart;
                    part = hit.part();
                    through = hit.at();
                }
            }
            if (through == null) {
                continue;
            }
            DamageSource base = WFDamageSources.create(vehicle.level(), DamageClass.PHYSICAL, round.shooter(), vehicle);
            DamageSource source = BlastDamageSource.of(base, victim, exit, through, part);
            float amount = damage;
            deliver(() -> victim.hurt(source, amount));
        }
    }
}
