package com.wf.combinedassault.ballistics.addon;

import com.wf.combinedassault.ballistics.Ballistics;
import com.wf.combinedassault.ballistics.VehicleBlastResolver;
import com.wf.combinedassault.ballistics.HitOutcomes;
import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullOutcome;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.armor.api.hull.WalkResult;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import com.wf.wflib.damage.api.event.ProjectileStrikeEvent;
import com.wf.wflib.explosion.api.BlastPayload;
import com.wf.wflib.round.api.Ricochet;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Strike results waiting for the projectile's own {@code hurt} call, which {@code DamageSystem} reads.
 * Server thread only; anything not taken dies at the end of the tick.
 */
public final class AddonStrikes {

    /** Key = {@code StrikeContext.key()}: projectile entity (identity equals) or round key (boxed long). */
    private static final Map<Object, AddonStrike.Result> PENDING = new HashMap<>();

    /**
     * A shaped charge's or EFP's strike on a vehicle this tick: what the panels did to it, for the warhead's own
     * {@code VehicleBlastResolver} pass that follows.
     */
    public record Jet(AddonStrike.Result result, ThreatKind kind, float caliberMm) {
    }

    private static final Map<AbstractVehicle, Jet> JETS = new IdentityHashMap<>();

    private AddonStrikes() {
    }

    @Nullable
    public static Jet jet(AbstractVehicle vehicle) {
        return JETS.isEmpty() ? null : JETS.get(vehicle);
    }

    /** What the round's own hit would have done to the hull, priced as the jet's incoming damage. */
    private static final Map<AbstractVehicle, Float> CONTACT = new IdentityHashMap<>();

    public static void contact(AbstractVehicle vehicle, float damage) {
        CONTACT.put(vehicle, damage);
    }

    /** Null: no round hit this vehicle this tick. */
    @Nullable
    public static Float contact(AbstractVehicle vehicle) {
        return CONTACT.isEmpty() ? null : CONTACT.get(vehicle);
    }

    public static void put(Object key, AddonStrike.Result result) {
        PENDING.put(key, result);
    }

    /** Kept to tick end: one round can {@code hurt} twice (TACZ splits armour-piercing damage). */
    @Nullable
    public static AddonStrike.Result get(@Nullable Object key) {
        return key == null || PENDING.isEmpty() ? null : PENDING.get(key);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PENDING.clear();
        JETS.clear();
        CONTACT.clear();
    }

    /** WFLib missiles and shells; TACZ rounds via taczwf. */
    @SubscribeEvent
    public static void onWfLibStrike(ProjectileStrikeEvent event) {
        if (!(event.target() instanceof AbstractVehicle vehicle)) {
            return;
        }
        Object projectile = event.key();
        Vec3 start = event.location().subtract(event.velocity());
        AddonStrike.Result result = AddonStrike.resolve(vehicle, event.threat(), start, event.velocity());
        if (event.threat().kind().ownsJet()) {
            JETS.put(vehicle, new Jet(result, event.threat().kind(), event.threat().caliberMm()));
        }
        switch (result.verdict()) {
            case CLEAR -> {
                armour(event, vehicle, event.threat(), event.location(), result.lead());
                IntegralEra.strike(vehicle, event.threat(), start, event.velocity());
            }
            case STOPPED, DUD -> event.setOutcome(ProjectileStrikeEvent.Outcome.DUD);
            case MISSED -> event.setOutcome(ProjectileStrikeEvent.Outcome.PASS);
            case THROUGH -> {
                if (!armour(event, vehicle, event.threat(), result.hull(), result.lead())) {
                    put(projectile, result);
                }
                IntegralEra.strike(vehicle, event.threat(), start, event.velocity());
            }
            case BURST -> {
                put(projectile, result);
                event.setDetonation(result.burst());
            }
        }
    }

    /**
     * Kinetic round vs the hull armour model. RICOCHET: flies on unhurt ({@link Ricochet#off}), shooter's readout.
     * Else, wflib rounds only (taczwf and missiles read nothing else than PROCEED): behind the entry plate with
     * penetration left on leaving => THROUGH at the exit speed; else STOP. {@code post_pen} round ({@code fillerKg > 0}):
     * entry plate defeated => THROUGH bursting just behind it, speed floored at {@link #BURST_SPEED}; else STOP = lost.
     *
     * @return ricochet answered (no damage follows)
     */
    private static boolean armour(ProjectileStrikeEvent event, AbstractVehicle vehicle, Threat threat, Vec3 at,
                                  AddonStrike.Lead lead) {
        ThreatKind kind = threat.kind();
        if (kind != ThreatKind.KINETIC && kind != ThreatKind.SMALL_ARMS || threat.penetrationMm() <= 0) {
            return false;
        }
        Vec3 start = event.location().subtract(event.velocity());
        HullResult hull = Ballistics.strike(vehicle, start, event.velocity(), at, threat, lead);
        if (hull == null) {
            return false;
        }
        if (hull.outcome() == HullOutcome.RICOCHET) {
            if (!event.canRicochet()) {
                return false;
            }
            event.ricochet(Ricochet.off(event.velocity(), hull.normal(), vehicle.getRandom().nextLong()));
            if (event.shooter() != null) {
                NeoForge.EVENT_BUS.post(new HitVehicleEvent(event.shooter().getUUID(), vehicle.getId(), at, event.velocity(),
                        event.threat().caliberMm(), 0, Component.translatable(HitOutcomes.translationKey(HullOutcome.RICOCHET))));
            }
            return true;
        }
        if (event.projectile() != null) {
            return false;
        }
        double speed = event.velocity().length();
        boolean burst = threat.fillerKg() > 0 && hull.through();
        if (hull.exits() || burst) {
            double out = Math.max(hull.exitSpeed((float) speed, event.penExponent()), burst ? speed * BURST_SPEED : 0);
            Vec3 dir = event.velocity().scale(1 / speed);
            event.through(dir.scale(out));
            if (burst) {
                WalkResult walk = hull.walk();
                float entry = walk.crossingAt(0);
                float depth = walk.crossings() > 1 ? Math.min(BURST_DEPTH, (walk.crossingAt(1) - entry) / 2) : BURST_DEPTH;
                Vec3 inside = start.add(dir.scale(entry + Math.max(depth, 0.0f)));
                event.setDetonation(inside);
                VehicleBlastResolver.interior(vehicle, event.shooter(), BlastPayload.blast(threat.fillerKg()), inside);
            }
        } else {
            event.setOutcome(ProjectileStrikeEvent.Outcome.STOP);
        }
        return false;
    }

    /** Speed fraction a bursting {@code post_pen} round keeps at least: it goes off at once, the figure only orients the trail. */
    private static final double BURST_SPEED = 0.1;
    /** Behind the entry plate, m: where a {@code post_pen} round goes off. */
    private static final float BURST_DEPTH = 0.3f;
}
