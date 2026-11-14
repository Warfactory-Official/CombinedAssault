package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.ballistics.addon.AddonPhysics;
import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.combinedassault.ballistics.addon.IntegralEra;
import com.wf.combinedassault.ballistics.addon.AddonStrikes;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.ballistics.spall.Spall;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.mixin.ballistics.ExplosionAccessor;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.damage.api.DamageClass;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import com.wf.wflib.damage.api.WFDamageSources;
import com.wf.wflib.explosion.api.BlastPayload;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A vehicle's answer to a warhead's {@link BlastPayload}, component by component, through the armour model:
 * <ul>
 *   <li>{@code blast}: per sampled ray ({@link BlastRays}, shared with the panels), {@link #hePen} at the hull
 *       distance vs the struck plate; through => modules + occupants, else the struck node's own system.</li>
 *   <li>{@code fragments}: the same sample rays as {@code KINETIC} threats, speed decaying with distance.</li>
 *   <li>{@code jet} / {@code efp}: one walk from the detonation point along the charge axis, panels as left by the
 *       round's strike ({@link AddonStrikes#jet}).</li>
 *   <li>{@code hesh}: contact plate transmits => spall fan behind it, no penetration.</li>
 *   <li>{@code thermal} and open-hatch blast: occupants of a seat whose hatch is open ({@link Spall#exposeCrew}).</li>
 *   <li>Centre inside the hull: no plate, modules + occupants direct.</li>
 * </ul>
 * Hull and part damage are collected into a {@link Damage} that {@code DamageSystem.hurtBlast} applies through one
 * {@code vehicle.hurt}.
 */
public final class VehicleBlastResolver {

    /** mm RHAe at contact per cbrt(kg TNT-eq). TODO estimate. */
    static final float HE_PEN_PER_CBRT_KG = 60.0f;
    /** Pen falls to half at {@code d = HE_CORE x cbrt(kg)}, m. TODO estimate. */
    static final float HE_CORE = 0.6f;
    /** mm RHAe of a steel fragment: {@code K x cbrt(massG) x (v / 1000)^1.43}. TODO estimate. */
    static final float FRAGMENT_PEN_K = 2.9f;
    /** Fragment speed decay length, m. TODO estimate. */
    public static final float FRAGMENT_DECAY = 80.0f;
    /** Damage per kJ a fragment carries into a hull. */
    static final float FRAGMENT_DAMAGE_PER_KJ = 1.0f;
    /** Hull damage a jet carries per mm of penetration when no round hit priced it ({@link AddonStrikes#contact}: proximity-fuzed missile). TODO estimate. */
    static final float JET_DAMAGE_PER_MM = 2.0f;
    static final float JET_REACH = 8.0f;
    /** HESH: pen figure that walks the plate = this x calibre ({@code mm <= 1.3 x calibre} spalls). */
    static final float HESH_THICKNESS_PER_CALIBRE = 1.3f;
    static final float HESH_HULL_SHARE = 0.5f;
    /** Centre deeper than this inside the main box = inside the hull, m. */
    static final float INTERIOR_DEPTH = 0.5f;
    static final float INTERIOR_HULL_SHARE = 0.5f;
    static final float INTERIOR_MODULE_SHARE = 2.0f;
    static final float CREW_BLAST_SHARE = 0.5f;
    static final int INTERIOR_RAYS = 32;
    /** Heat damage per block of thermal radius. TODO estimate. */
    static final float THERMAL_HEAT_PER_BLOCK = 4.0f;
    private static final float THREAT_NO_RICOCHET = 90.0f;
    /** Blast pressure ignores the plate's slope: {@code cos^n} ~ 1 (a {@code Threat} needs {@code n > 0}). */
    private static final float NO_SLOPE = 1.0e-3f;

    /** What one blast did to one vehicle, for {@code DamageSystem.hurtBlast}. */
    static final class Damage {
        float hull;
        final Map<PartUnit<?>, Float> parts = new IdentityHashMap<>();
        /** Strongest ray: where the armour region wears. */
        @Nullable
        Vec3 at;
        @Nullable
        Vector3f normal;
        float incoming;
        boolean armoured;

        void part(PartUnit<?> part, float amount) {
            parts.merge(part, amount, Float::sum);
        }

        boolean any() {
            return hull > 0 || !parts.isEmpty() || at != null;
        }
    }

    private static final ThreadLocal<Map<AbstractVehicle, Damage>> PENDING = ThreadLocal.withInitial(IdentityHashMap::new);

    private VehicleBlastResolver() {
    }

    @Nullable
    static Damage pending(AbstractVehicle vehicle) {
        Map<AbstractVehicle, Damage> pending = PENDING.get();
        return pending.isEmpty() ? null : pending.get(vehicle);
    }

    /** TNT-eq kg at hull distance {@code d} m => mm RHAe the blast wave punches. */
    public static float hePen(float tntKg, double d) {
        float c = (float) Math.cbrt(tntKg);
        double q = d / (HE_CORE * c);
        return (float) (HE_PEN_PER_CBRT_KG * c / (1 + q * q));
    }

    /** Blast radius ({@code size}) of a charge of {@code tntKg}: inverse of {@link BlastPayload#kgForSize}. */
    public static float sizeForKg(float tntKg) {
        return (float) Math.cbrt(tntKg / BlastPayload.KG_PER_SIZE_CUBED);
    }

    /**
     * @param source vanilla explosion or the AEF's {@code compat}
     * @param aef    the running AEF, null for a vanilla explosion (payload then synthesised from its radius)
     */
    public static void resolve(AbstractVehicle vehicle, Explosion source, @Nullable ExplosionAEF aef, BlastPayload payload,
                               Vec3 centre) {
        if (vehicle.level().isClientSide() || vehicle.ignoreExplosion(source)) {
            return;
        }
        float size = aef != null ? aef.size : ((ExplosionAccessor) source).getRadius();
        Entity direct = source.getDirectSourceEntity();
        Entity causing = source.getIndirectSourceEntity();
        run(vehicle, direct, causing, aef, payload, centre, size);
    }

    /** Burst inside the hull (a {@code post_pen} round past its entry plate): no plate between, crew and modules direct. */
    public static void interior(AbstractVehicle vehicle, @Nullable Entity shooter, BlastPayload payload, Vec3 centre) {
        if (vehicle.level().isClientSide() || payload.blast() == null) {
            return;
        }
        run(vehicle, null, shooter, null, payload, centre, sizeForKg(payload.blast().tntKg()));
    }

    private static void run(AbstractVehicle vehicle, @Nullable Entity direct, @Nullable Entity causing,
                            @Nullable ExplosionAEF aef, BlastPayload payload, Vec3 centre, float size) {
        boolean armoured = vehicle.armorProfile != null && vehicle.armorProfile.isDefined();
        Ctx c = new Ctx(vehicle, direct, causing, aef, centre, size, armoured);
        if (inside(vehicle, centre)) {
            c.interiorBlast(payload);
        } else {
            if (payload.blast() != null || payload.fragments() != null) {
                c.rays(payload);
            }
            if (payload.jet() != null || payload.efp() != null) {
                c.jet(payload);
            }
            if (payload.hesh() != null) {
                c.hesh(payload.hesh());
            }
        }
        if (payload.thermal() != null) {
            Spall.exposeCrew(vehicle, centre, payload.thermal().radius(), payload.thermal().radius() * THERMAL_HEAT_PER_BLOCK,
                    causing != null ? causing : direct);
        }
        c.apply();
    }

    /** Deeper than {@link #INTERIOR_DEPTH} inside the main box on every axis. */
    static boolean inside(AbstractVehicle vehicle, Vec3 point) {
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        if (main == null) {
            return false;
        }
        OBB box = main.obb();
        Vector3f[] axes = box.getAxes();
        Vector3f ext = box.extents();
        double rx = point.x - box.centerX();
        double ry = point.y - box.centerY();
        double rz = point.z - box.centerZ();
        return Math.abs(rx * axes[0].x + ry * axes[0].y + rz * axes[0].z) < ext.x - INTERIOR_DEPTH
                && Math.abs(rx * axes[1].x + ry * axes[1].y + rz * axes[1].z) < ext.y - INTERIOR_DEPTH
                && Math.abs(rx * axes[2].x + ry * axes[2].y + rz * axes[2].z) < ext.z - INTERIOR_DEPTH;
    }

    /** One blast on one vehicle. */
    private static final class Ctx {
        final AbstractVehicle vehicle;
        @Nullable
        final Entity direct;
        @Nullable
        final Entity causing;
        @Nullable
        final ExplosionAEF aef;
        final Vec3 centre;
        final float size;
        final float reach;
        final Damage damage = new Damage();
        final HullComponents systems;
        @Nullable
        final VehicleHits hits;
        /** Unarmoured: hull takes the weighted blast as the legacy path priced it. */
        float legacy;

        Ctx(AbstractVehicle vehicle, @Nullable Entity direct, @Nullable Entity causing, @Nullable ExplosionAEF aef,
            Vec3 centre, float size, boolean armoured) {
            this.vehicle = vehicle;
            this.direct = direct;
            this.causing = causing;
            this.aef = aef;
            this.centre = centre;
            this.size = size;
            this.reach = size * 2.0f;
            this.systems = vehicle.hullComponents();
            this.hits = vehicle.hits();
            this.damage.armoured = armoured;
        }

        @Nullable
        Entity shooter() {
            return causing != null ? causing : direct;
        }

        /** Old blast curve at {@code s} = (1 - distance / reach) x share left. */
        float incoming(float s) {
            return (int) ((s * s + s) / 2.0f * 7.0f * reach + 1.0f);
        }

        void rays(BlastPayload payload) {
            BlastRays.Pass pass = BlastRays.cast(vehicle, centre, size, aef);
            if (pass.count == 0) {
                return;
            }
            double weights = 0;
            float[] weight = new float[pass.count];
            for (int j = 0; j < pass.count; j++) {
                weight[j] = BlastRays.falloff(pass.distance[j] / reach);
                weights += weight[j];
            }
            if (weights <= 0) {
                return;
            }
            BlastPayload.Fragments fragments = payload.fragments();
            float perSample = fragments == null ? 0
                    : fragments.count() * (float) pass.hullRays / (BlastRays.RAYS * pass.count);
            float fragmentCaliber = fragments == null ? 0
                    : 10.0f * (float) Math.cbrt(6.0 * fragments.massG() / (7.85 * Math.PI));
            for (int j = 0; j < pass.count; j++) {
                Vec3 dir = pass.dir(j);
                float d = pass.distance[j];
                Vec3 point = centre.add(dir.scale(d));
                if (!seen(point, dir) || pass.power[j] <= 0) {
                    continue;
                }
                if (payload.blast() != null) {
                    float s = (1 - d / reach) * pass.power[j];
                    if (s > 0) {
                        float share = (float) (weight[j] / weights);
                        float incoming = incoming(s);
                        if (damage.armoured) {
                            Threat he = new Threat(ThreatKind.HE, 0.0f, hePen(payload.blast().tntKg(), d), 1.0f,
                                    THREAT_NO_RICOCHET, NO_SLOPE, 0.0f, 0.0f);
                            strike(centre, dir, point, he, incoming, share, false, null);
                        } else {
                            legacy += incoming * share;
                        }
                    }
                }
                if (fragments != null) {
                    float v = fragments.speed() * (float) Math.exp(-d / FRAGMENT_DECAY) * pass.power[j];
                    float pen = FRAGMENT_PEN_K * (float) Math.cbrt(fragments.massG()) * (float) Math.pow(v / 1000.0f, 1.43);
                    float energyKj = 0.5f * fragments.massG() / 1000.0f * v * v / 1000.0f;
                    float incoming = energyKj * FRAGMENT_DAMAGE_PER_KJ;
                    if (damage.armoured && pen > 0 && incoming > 0) {
                        Threat frag = new Threat(ThreatKind.KINETIC, fragmentCaliber, pen);
                        strike(centre, dir, point, frag, incoming, perSample, false, null);
                    } else if (!damage.armoured) {
                        legacy += incoming * perSample;
                    }
                }
            }
            crewBlast(pass.exposure);
        }

        /** Block cover between the centre and the hull point; fluids do not block. */
        boolean seen(Vec3 point, Vec3 dir) {
            HitResult clip = vehicle.level().clip(new ClipContext(centre, point.subtract(dir.scale(0.05)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
            return clip.getType() == HitResult.Type.MISS;
        }

        /**
         * One resolved ray: hull + part damage x {@code share}; through => modules in line order and the occupants'
         * line + fans, else the struck node's own system.
         */
        @Nullable
        HullResult strike(Vec3 start, Vec3 dir, Vec3 point, Threat threat, float incoming, float share, boolean jet,
                          @Nullable AddonStrike.Lead lead) {
            HullResult result = Ballistics.blast(vehicle, start, dir, point, threat, incoming, lead);
            if (jet) {
                IntegralEra.commit(vehicle, result == null ? null : result.walk(), point);
            }
            if (result == null) {
                damage.hull += incoming * share;
                return null;
            }
            float hull = result.hullDamage() * share;
            damage.hull += hull;
            if (incoming * share > damage.incoming) {
                damage.incoming = incoming * share;
                damage.at = point;
                damage.normal = result.faceNormal().toVector3f();
            }
            if (hits != null) {
                VehicleHits.Hit surface = hits.surface(point.subtract(dir.scale(0.1)), point.add(dir.scale(0.1)));
                PartUnit<?> part = surface == null ? null : hits.part(surface.node());
                if (part != null && result.partDamage() > 0) {
                    damage.part(part, result.partDamage() * share);
                }
            }
            if (!systems.isEmpty() && hull > 0) {
                DamageSystem.hurtResolved(vehicle, systems, start, dir, result, point, hull);
            }
            if (result.through()) {
                Spall.onPenetration(vehicle, shooter(), start, dir, jet, result, incoming, share, hull);
            }
            return result;
        }

        /** Occupants behind an open hatch feel the blast on top of what armour lets through. */
        void crewBlast(float exposure) {
            for (AbstractVehicle.Seat seat : vehicle.seats) {
                if (seat.passengerId == -1 || !vehicle.hatchOpen(seat)
                        || !(vehicle.level().getEntity(seat.passengerId) instanceof LivingEntity victim) || !victim.isAlive()) {
                    continue;
                }
                Vec3 eye = victim.getEyePosition();
                double distance = eye.distanceTo(centre);
                if (distance >= reach || hits != null && hits.surface(centre, eye) != null) {
                    continue;
                }
                float s = (float) (1 - distance / reach) * exposure;
                crew(victim, centre, eye, incoming(s) * CREW_BLAST_SHARE);
            }
        }

        void crew(LivingEntity victim, Vec3 from, Vec3 to, float amount) {
            DamageSource source = com.wf.wflib.damage.api.BlastDamageSource.of(
                    WFDamageSources.create(vehicle.level(), DamageClass.EXPLOSIVE, shooter(), vehicle), victim, from, to, null);
            Spall.deliver(() -> victim.hurt(source, amount));
        }

        void jet(BlastPayload payload) {
            BlastPayload.Jet jet = payload.jet();
            BlastPayload.Efp efp = payload.efp();
            boolean slug = jet == null;
            float pen = slug ? efp.penMm() : jet.penMm();
            AddonStrikes.Jet prior = AddonStrikes.jet(vehicle);
            ThreatKind kind = prior != null ? prior.kind() : slug ? ThreatKind.EFP : ThreatKind.HEAT;
            float caliber = prior != null ? prior.caliberMm() : 0.0f;
            Vec3 axis = aef != null && aef.jetAxis() != null ? aef.jetAxis()
                    : vehicle.getBoundingBox().getCenter().subtract(centre).normalize();
            AddonStrike.Result panels = prior != null ? prior.result()
                    : AddonStrike.resolve(vehicle, new Threat(kind, caliber, pen), centre, axis);
            Vec3 origin = centre;
            AddonStrike.Lead lead = panels.lead();
            Vec3 hull = panels.hull();
            switch (panels.verdict()) {
                case STOPPED, DUD, MISSED -> {
                    return;
                }
                case BURST -> {
                    if (hull == null) {
                        return;
                    }
                    origin = panels.burst();
                }
                case THROUGH, CLEAR -> {
                }
            }
            Vec3 start = origin.subtract(axis.scale(0.15));
            if (hull == null) {
                hull = VectorUtil.closestHullHit(vehicle, start, origin.add(axis.scale(JET_REACH)));
                if (hull == null) {
                    return;
                }
            }
            if (prior == null && panels.verdict() != AddonStrike.Verdict.BURST) {
                lead = lead.withGap(hull.distanceTo(origin));
            }
            if (!(pen > 0) || lead.dead()) {
                return;
            }
            Threat threat = new Threat(kind, caliber, pen, 1.0f, THREAT_NO_RICOCHET, kind.slopeExp, 0.0f, 0.0f);
            Float contact = AddonStrikes.contact(vehicle);
            float incoming = contact != null ? contact : pen * JET_DAMAGE_PER_MM;
            if (!damage.armoured) {
                legacy += incoming;
                return;
            }
            HullResult result = strike(start, axis, hull, threat, incoming, 1.0f, true, lead);
            Entity attacker = shooter();
            if (attacker != null) {
                String key = result == null ? "message.vehicle.damage_system.hit"
                        : HitOutcomes.translationKey(result.outcome());
                NeoForge.EVENT_BUS.post(new HitVehicleEvent(attacker.getUUID(), vehicle.getId(), hull, axis, caliber,
                        result == null ? incoming : result.hullDamage(), Component.translatable(key)));
            }
        }

        void hesh(BlastPayload.Hesh hesh) {
            if (!damage.armoured) {
                return;
            }
            Vec3 contact = VehicleBlast.nearestHullPoint(vehicle, centre);
            Vec3 toward = contact.subtract(centre);
            Vec3 dir = toward.lengthSqr() < 1.0e-6 ? vehicle.getBoundingBox().getCenter().subtract(centre).normalize()
                    : toward.normalize();
            Vec3 start = contact.subtract(dir.scale(0.2));
            Vec3 point = VectorUtil.closestHullHit(vehicle, start, contact.add(dir.scale(0.3)));
            if (point == null) {
                return;
            }
            Threat threat = new Threat(ThreatKind.HESH, hesh.calibreMm(), hesh.calibreMm() * HESH_THICKNESS_PER_CALIBRE, 1.0f,
                    THREAT_NO_RICOCHET, NO_SLOPE, 0.0f, 0.0f);
            float incoming = incoming(1.0f);
            HullResult result = Ballistics.blast(vehicle, start, dir, point, threat, incoming, null);
            if (result == null) {
                return;
            }
            float transmit = result.walk().transmit();
            damage.hull += result.hullDamage() * transmit * HESH_HULL_SHARE;
            if (transmit > 0 && result.interiorPen() > 0) {
                float at = result.walk().crossings() > 1 ? result.walk().crossingAt(1) : result.walk().crossingAt(0) + 0.05f;
                Spall.onSquash(vehicle, shooter(), start.add(dir.scale(at + 0.05)), dir, incoming * transmit,
                        result.hullDamage() * transmit);
            }
            if (damage.at == null) {
                damage.at = point;
                damage.normal = result.faceNormal().toVector3f();
                damage.incoming = incoming;
            }
        }

        /** No plate between charge and anything: hull, modules by line, occupants by distance. */
        void interiorBlast(BlastPayload payload) {
            if (payload.blast() == null) {
                return;
            }
            float incoming = incoming(1.0f);
            damage.hull += incoming * INTERIOR_HULL_SHARE;
            damage.at = centre;
            damage.incoming = 0;
            if (hits != null && !systems.isEmpty()) {
                int stride = BlastRays.RAYS / INTERIOR_RAYS;
                for (int j = 0; j < INTERIOR_RAYS; j++) {
                    Vec3 dir = BlastRays.dir(j * stride);
                    VehicleHits.Hit module = hits.module(centre, centre.add(dir.scale(reach)));
                    if (module != null) {
                        systems.hurt(hits.model().nodes().get(module.node()).systemId(),
                                incoming * BlastRays.falloff(module.fraction()) * INTERIOR_MODULE_SHARE / INTERIOR_RAYS);
                    }
                }
            }
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof LivingEntity victim && victim.isAlive()) {
                    Vec3 eye = victim.getEyePosition();
                    double distance = eye.distanceTo(centre);
                    if (distance < reach) {
                        crew(victim, centre, eye, incoming((float) (1 - distance / reach)) * CREW_BLAST_SHARE);
                    }
                }
            }
        }

        void apply() {
            if (!damage.armoured) {
                if (legacy <= 0) {
                    return;
                }
                damage.hull = legacy;
                damage.at = null;
            } else if (!damage.any()) {
                return;
            }
            Vec3 at = damage.at != null ? damage.at : VehicleBlast.nearestHullPoint(vehicle, centre);
            DamageSource source = VehicleDamageTypes.Sources.explosion(vehicle.level().registryAccess(), direct, causing, at);
            Map<AbstractVehicle, Damage> pending = PENDING.get();
            if (damage.armoured) {
                pending.put(vehicle, damage);
            }
            try {
                vehicle.hurt(source, damage.hull);
            } finally {
                pending.remove(vehicle);
            }
        }
    }
}
