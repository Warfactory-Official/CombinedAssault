package com.wf.combinedassault.ballistics.addon;

import com.wf.combinedassault.ballistics.HitOutcomes;
import com.wf.wflib.damage.api.event.ProjectileStrikeEvent;
import com.wf.wflib.damage.api.ThreatKind;
import com.wf.wflib.round.api.Ricochet;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.Ballistics;
import com.wf.wflib.armor.api.hull.HullOutcome;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;

/**
 * Strike results waiting for the projectile's own {@code hurt} call, which {@code DamageSystem} reads.
 * Server thread only; anything not taken dies at the end of the tick.
 */
public final class AddonStrikes {

    /** Key = {@code StrikeContext.key()}: projectile entity (identity equals) or round key (boxed long). */
    private static final Map<Object, AddonStrike.Result> PENDING = new HashMap<>();

    private AddonStrikes() {
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
    }

    /** WFLib missiles and shells; TACZ rounds via taczwf. */
    @SubscribeEvent
    public static void onWfLibStrike(ProjectileStrikeEvent event) {
        if (!(event.target() instanceof AbstractVehicle vehicle)) {
            return;
        }
        Object projectile = event.key();
        AddonStrike.Result result = AddonStrike.resolve(vehicle, event.threat(),
                event.location().subtract(event.velocity()), event.velocity());
        switch (result.verdict()) {
            case CLEAR -> glance(event, vehicle, event.threat().penetrationMm(), event.location());
            case STOPPED, DUD -> event.setOutcome(ProjectileStrikeEvent.Outcome.DUD);
            case MISSED -> event.setOutcome(ProjectileStrikeEvent.Outcome.PASS);
            case THROUGH -> {
                if (!glance(event, vehicle, event.threat().penetrationMm() * result.penetrationScale(),
                        result.hull())) {
                    put(projectile, result);
                }
            }
            case BURST -> {
                put(projectile, result);
                event.setDetonation(result.burst());
            }
        }
    }

    /** Kinetic round the hull armour model skates off (RICOCHET): flies on unhurt ({@link Ricochet#off}); shooter's readout. */
    private static boolean glance(ProjectileStrikeEvent event, AbstractVehicle vehicle, float penetration, Vec3 at) {
        ThreatKind kind = event.threat().kind();
        if (!event.canRicochet() || kind != ThreatKind.KINETIC && kind != ThreatKind.SMALL_ARMS) {
            return false;
        }
        Vec3 n = Ballistics.ricochet(vehicle, event.location().subtract(event.velocity()), event.velocity(), at,
                penetration);
        if (n == null) {
            return false;
        }
        event.ricochet(Ricochet.off(event.velocity(), n, vehicle.getRandom().nextLong()));
        if (event.shooter() != null) {
            NeoForge.EVENT_BUS.post(new HitVehicleEvent(event.shooter().getUUID(), vehicle.getId(), at, event.velocity(),
                    event.threat().caliberMm(), 0, Component.translatable(HitOutcomes.translationKey(HullOutcome.RICOCHET))));
        }
        return true;
    }
}
