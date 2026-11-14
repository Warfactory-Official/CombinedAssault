package com.wf.combinedassault.ballistics.spall;

import com.norwood.ahf.part.HitboxPart;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.armor.api.hull.WalkResult;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Behind-armour effects of a defeated plate, only when {@link HullResult#through}:
 * <ul>
 *   <li>Penetrator line: entry plate -> where the round dies / leaves; occupants on it take {@code incoming x
 *       residual / pen}.</li>
 *   <li>Fan per defeated inward face (<= {@link #MAX_FANS}): energy {@code incoming x ramp x residual / pen x layer
 *       spall}; occupants + internal systems.</li>
 *   <li>Stopped / ricocheted / PARTIAL: nothing.</li>
 * </ul>
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
    static final float EXIT_OFFSET = 0.25f;
    /** Fans per penetration, nearest defeated faces first. */
    static final int MAX_FANS = 4;
    /** Penetrator line past a lone entry plate, m. */
    static final float LINE_REACH = 4.0f;
    static final float KINETIC_CONE_DEG = 30.0f;
    static final float JET_CONE_DEG = 8.0f;
    static final float SQUASH_CONE_DEG = 50.0f;
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
     * The penetrator line strikes crew first, then a fan per defeated inner face adds to it.
     *
     * @param incoming     damage before armour
     * @param systemAmount what the struck system would take whole
     * @return fragments flew: the caller lowers the struck system's share to {@link #DIRECT_SHARE}
     */
    public static boolean onPenetration(AbstractVehicle vehicle, StrikeContext round, HullResult result,
                                        float incoming, float systemAmount) {
        if (round.velocity().lengthSqr() < 1.0e-12) {
            return false;
        }
        return onPenetration(vehicle, round.shooter(), round.position(), round.velocity().normalize(),
                round.threat().kind().isShapedCharge(), result, incoming, 1.0f, systemAmount);
    }

    /**
     * As {@link #onPenetration(AbstractVehicle, StrikeContext, HullResult, float, float)} for a line that is not a
     * round: a blast ray or a warhead jet, {@code start} = where {@code result} was resolved from.
     *
     * @param share fraction of {@code incoming} this line carries (a sampled ray stands for many)
     */
    public static boolean onPenetration(AbstractVehicle vehicle, @Nullable Entity shooter, Vec3 start, Vec3 dir,
                                        boolean jet, HullResult result, float incoming, float share, float systemAmount) {
        if (!result.through() || incoming <= 0) {
            return false;
        }
        WalkResult walk = result.walk();
        float pen = result.penetration();
        float ramp = Math.min(1.0f, Math.max(0.0f, result.hullDamage() / incoming));
        int crossings = walk.crossings();
        List<Burst> bursts = new ArrayList<>(2);
        float total = 0;
        for (int k = 0; k < crossings && bursts.size() < MAX_FANS; k++) {
            int last = -1;
            boolean defeated = true;
            for (int i = 0; i < walk.layers(); i++) {
                if (walk.crossing(i) == k) {
                    last = i;
                    defeated &= i < walk.defeated();
                }
            }
            if (last < 0 || !defeated || !walk.crossingEntering(k)) {
                continue;
            }
            float energy = incoming * share * ramp * Math.min(1.0f, walk.residualAfter(last) / pen) * walk.spall(last);
            if (energy <= 0) {
                continue;
            }
            float at = walk.crossingAt(k);
            float off = k + 1 < crossings ? Math.min(EXIT_OFFSET, (walk.crossingAt(k + 1) - at) / 2) : EXIT_OFFSET;
            Vec3 origin = start.add(dir.scale(at + Math.max(off, 0.0f)));
            int count = Math.max(1, Math.min(MAX_FRAGMENTS, (int) Math.ceil(energy / FRAGMENT_UNIT)));
            if (jet) {
                count = Math.max(1, count / JET_DIVISOR);
            }
            long seed = Seeded.mix(Math.round((origin.x - vehicle.getX()) * 1000)
                    ^ Seeded.mix(Math.round((origin.y - vehicle.getY()) * 1000)
                    ^ Seeded.mix(Math.round((origin.z - vehicle.getZ()) * 1000))) ^ k);
            bursts.add(new Burst(origin, fan(dir, jet ? JET_CONE_DEG : KINETIC_CONE_DEG, count, seed), energy,
                    energy / count * CREW_SHARE * (jet ? JET_HEAT : 1.0f)));
            total += energy;
        }
        float lineFrom = walk.crossingAt(0) + Math.min(EXIT_OFFSET, 0.1f);
        float lineTo = crossings <= 1 ? walk.crossingAt(0) + LINE_REACH
                : walk.through() ? walk.crossingAt(crossings - 1) : walk.crossingAt(walk.crossing(walk.stoppedAt()));
        Vec3 from = start.add(dir.scale(lineFrom));
        Vec3 to = start.add(dir.scale(Math.max(lineTo, lineFrom)));
        for (Burst burst : bursts) {
            strikeSystems(vehicle, burst.origin, burst.fan, systemAmount * (1 - DIRECT_SHARE) * burst.energy / total
                    / burst.fan.length);
        }
        strikeCrew(vehicle, shooter, bursts, from, to, walk, start, pen, incoming * share);
        return !bursts.isEmpty();
    }

    /**
     * HESH: the slab spalls off the inner face of a plate that transmits it; no penetrator, one fan from
     * {@code inner} along {@code dir}. Occupants + internal systems.
     *
     * @param energy       damage the fan carries
     * @param systemAmount what the struck system would take whole
     */
    public static boolean onSquash(AbstractVehicle vehicle, @Nullable Entity shooter, Vec3 inner, Vec3 dir, float energy,
                                   float systemAmount) {
        if (energy <= 0) {
            return false;
        }
        int count = Math.max(1, Math.min(MAX_FRAGMENTS, (int) Math.ceil(energy / FRAGMENT_UNIT)));
        long seed = Seeded.mix(Math.round((inner.x - vehicle.getX()) * 1000)
                ^ Seeded.mix(Math.round((inner.y - vehicle.getY()) * 1000)
                ^ Seeded.mix(Math.round((inner.z - vehicle.getZ()) * 1000))));
        Burst burst = new Burst(inner, fan(dir, SQUASH_CONE_DEG, count, seed), energy, energy / count * CREW_SHARE);
        strikeSystems(vehicle, inner, burst.fan, systemAmount * (1 - DIRECT_SHARE) / burst.fan.length);
        strikeCrew(vehicle, shooter, List.of(burst), null, null, null, inner, 1.0f, 0.0f);
        return true;
    }

    /**
     * Heat or gas reaching the seats of {@code vehicle}: occupants of a seat whose hatch is open and within
     * {@code radius} of {@code at} burn and take {@code heat x (1 - distance / radius)}; a closed hatch shields.
     */
    public static void exposeCrew(AbstractVehicle vehicle, Vec3 at, float radius, float heat, @Nullable Entity attacker) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == -1 || !vehicle.hatchOpen(seat)
                    || !(vehicle.level().getEntity(seat.passengerId) instanceof LivingEntity victim) || !victim.isAlive()) {
                continue;
            }
            double distance = victim.getEyePosition().distanceTo(at);
            if (distance >= radius) {
                continue;
            }
            victim.igniteForSeconds(BURN_SECONDS);
            float amount = heat * (float) (1 - distance / radius);
            if (amount > 0) {
                DamageSource source = WFDamageSources.create(vehicle.level(), DamageClass.FIRE, attacker, vehicle);
                deliver(() -> victim.hurt(source, amount));
            }
        }
    }

    /** Fan of fragments from {@code origin}; {@code energy} = damage it carries. */
    private record Burst(Vec3 origin, Vec3[] fan, float energy, float perFragment) {
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

    private static void strikeCrew(AbstractVehicle vehicle, @Nullable Entity shooter, List<Burst> bursts, @Nullable Vec3 lineFrom,
                                   @Nullable Vec3 lineTo, @Nullable WalkResult walk, Vec3 start, float pen, float incoming) {
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
            Rounds.LiveHit line = lineFrom == null ? null : Rounds.hitLive(victim, lineFrom, lineTo);
            if (line != null) {
                float left = walk.residualAt((float) start.distanceTo(line.at()), pen);
                float onLine = incoming * Math.min(1.0f, Math.max(0.0f, left / pen));
                if (onLine > 0) {
                    damage += onLine;
                    part = line.part();
                    through = line.at();
                    best = line.part() == null ? onLine : byPart.merge(line.part(), onLine, Float::sum);
                }
            }
            for (Burst burst : bursts) {
                if (burst.perFragment <= 0) {
                    continue;
                }
                for (Vec3 d : burst.fan) {
                    Rounds.LiveHit hit = Rounds.hitLive(victim, burst.origin, burst.origin.add(d.scale(RANGE)));
                    if (hit == null) {
                        continue;
                    }
                    damage += burst.perFragment;
                    float onPart = hit.part() == null ? burst.perFragment
                            : byPart.merge(hit.part(), burst.perFragment, Float::sum);
                    if (through == null || onPart > best) {
                        best = onPart;
                        part = hit.part();
                        through = hit.at();
                    }
                }
            }
            if (through == null) {
                continue;
            }
            DamageSource base = WFDamageSources.create(vehicle.level(), DamageClass.PHYSICAL, shooter, vehicle);
            DamageSource source = BlastDamageSource.of(base, victim, lineFrom == null ? bursts.get(0).origin : lineFrom, through, part);
            float amount = damage;
            deliver(() -> victim.hurt(source, amount));
        }
    }
}
