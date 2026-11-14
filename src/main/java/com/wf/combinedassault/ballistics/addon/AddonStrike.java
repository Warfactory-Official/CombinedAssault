package com.wf.combinedassault.ballistics.addon;

import com.wf.combinedassault.ballistics.BlastRays;
import com.wf.combinedassault.ballistics.VehicleBlastResolver;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.EraType;
import com.wf.wflib.armor.api.hull.PlateWalk;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import com.wf.wflib.explosion.api.BlastPayload;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Consumer;

/** A round's path through a vehicle's ERA and slat, applied: bricks spent, bars bent. Server side. */
public final class AddonStrike {

    /** Past the tick's own travel: a panel's gap to the hull, generously. */
    private static final double HULL_REACH = 6.0;
    private static final float BURST_NEIGHBOUR_BEND = 0.5f;
    private static final float DUD_BEND = 0.35f;
    private static final float STOP_DENT = 0.1f;
    private static final float MIN_BLAST_BEND = 0.05f;
    /** Size-4 blast 1 block off a face-on bar => bend ~1. */
    private static final float BLAST_BEND = 2.0f;

    public enum Verdict {
        /** No installed panel on the path: stock handling. */
        CLEAR,
        /** Reached the hull at {@link Result#hull}, degraded by {@link Result#penetrationScale}. */
        THROUGH,
        /** Warhead fired on a panel at {@link Result#burst}; a jet reaches {@link Result#hull} if non-null. */
        BURST,
        /** Bullet caught by the panel. */
        STOPPED,
        /** Fuze failed in the cage. */
        DUD,
        /** Through the panels and past the hull. */
        MISSED
    }

    /**
     * The panels in front of the hull as layers of the plate walk: what they cut from a round's penetration, in
     * path order ({@link com.wf.wflib.armor.api.hull.HullResolver#resolve} feeds it to the walk before the entry plate).
     * Built once per strike by {@link #resolve}; panels hit are few.
     */
    public static final class Lead implements Consumer<PlateWalk> {

        public static final Lead NONE = new Lead();

        private float[] keep = new float[2];
        private float[] gap = new float[2];
        private int n;

        /** Nothing past this layer: keep {@code keep} of the budget. */
        void cut(float factor) {
            if (factor < 1.0f) {
                add(factor, 0.0f);
            }
        }

        /** Air of {@code metres} between the burst and the hull. */
        void gap(double metres) {
            if (metres > 0) {
                add(1.0f, (float) metres);
            }
        }

        private void add(float k, float g) {
            if (n == keep.length) {
                keep = java.util.Arrays.copyOf(keep, n * 2);
                gap = java.util.Arrays.copyOf(gap, n * 2);
            }
            keep[n] = k;
            gap[n++] = g;
        }

        public boolean isEmpty() {
            return n == 0;
        }

        /** A copy with {@code metres} of air after the layers. */
        public Lead withGap(double metres) {
            Lead copy = new Lead();
            copy.keep = java.util.Arrays.copyOf(keep, Math.max(keep.length, n + 1));
            copy.gap = java.util.Arrays.copyOf(gap, copy.keep.length);
            copy.n = n;
            copy.gap(metres);
            return copy;
        }

        /** Some layer keeps nothing: the round has no penetration left to resolve. */
        public boolean dead() {
            for (int i = 0; i < n; i++) {
                if (keep[i] <= 0.0f) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void accept(PlateWalk walk) {
            for (int i = 0; i < n; i++) {
                if (gap[i] > 0) {
                    walk.gap(gap[i]);
                } else {
                    walk.cut(keep[i]);
                }
            }
        }

        /** Fraction of a {@code kind} round's penetration the layers keep (the walk's own rule). */
        public float scale(ThreatKind kind) {
            float scale = 1.0f;
            for (int i = 0; i < n; i++) {
                scale *= gap[i] > 0 ? AddonPhysics.standoffScale(kind, gap[i]) : keep[i];
            }
            return scale;
        }
    }

    /**
     * @param lead        the panels as walk layers
     * @param damageScale for rounds without an armour-model penetration figure, the same cut applied to damage
     */
    public record Result(Verdict verdict, @Nullable Vec3 burst, @Nullable Vec3 hull, Lead lead, float damageScale) {

        static final Result CLEAR = new Result(Verdict.CLEAR, null, null, Lead.NONE, 1);
    }

    /** A round that reached nothing: its penetration is gone. */
    private static final Lead DEAD = new Lead();

    static {
        DEAD.cut(0.0f);
    }

    private AddonStrike() {
    }

    public static Result resolve(AbstractVehicle vehicle, Threat threat, Vec3 start, Vec3 velocity) {
        AddonArmorLayer layer = vehicle.addonArmor();
        double speed = velocity.length();
        if (!layer.anyInstalled() || speed < 1.0e-6 || !(vehicle.level() instanceof ServerLevel level)) {
            return Result.CLEAR;
        }
        Vec3 direction = velocity.scale(1 / speed);
        Vec3 end = start.add(direction.scale(speed + HULL_REACH));
        OBB.SegmentHit hull = OBB.first(vehicle.getOBBs(), start, end);
        double hullFraction = hull == null ? 1.0
                : (hull.distance() + AddonArmorLayer.SKIN_INSET) / (speed + HULL_REACH);
        List<AddonArmorLayer.Hit> hits = layer.trace(vehicle, start, end, true);
        hits.removeIf(hit -> hit.fraction() > hullFraction);
        if (hits.isEmpty()) {
            return Result.CLEAR;
        }

        Lead lead = new Lead();
        float scale = 1.0f;
        Vec3 burst = null;
        boolean changed = false;
        for (AddonArmorLayer.Hit hit : hits) {
            AddonMount mount = layer.mounts().get(hit.mount());
            AddonMountState state = layer.state(hit.mount());
            if (state.fit.kind == AddonKind.ERA) {
                int cell = mount.cellAt(hit.x(), hit.y());
                if (!state.isLive(cell)) {
                    continue;
                }
                EraType type = state.fit.era();
                EraType.Strike era = type.strike(threat, hit.cos(), mount.cellEdgeFraction(hit.x(), hit.y()));
                if (era.initiated()) {
                    changed = true;
                    scale *= era.keep();
                    lead.cut(era.keep());
                    detonate(vehicle, layer, state, hit.mount(), cell, type, level);
                }
                if (burst == null) {
                    switch (threat.kind()) {
                        case HEAT, TANDEM_HEAT, HE, HESH -> burst = hit.point();
                        case SMALL_ARMS -> {
                            return finish(layer, changed, stopped(level, hit.point()));
                        }
                        case KINETIC, EFP -> {
                        }
                    }
                    if (threat.kind() == ThreatKind.HE || threat.kind() == ThreatKind.HESH) {
                        break;
                    }
                }
                continue;
            }
            if (burst != null) {
                // A formed jet goes through a cage.
                continue;
            }
            AddonPhysics.SlatOutcome slat = AddonPhysics.slat(mount, state, threat, hit.y(), level.getRandom());
            switch (slat.verdict()) {
                case MISS -> {
                }
                case STOPPED -> {
                    dent(state, slat.contact(), STOP_DENT);
                    return finish(layer, true, stopped(level, hit.point()));
                }
                case PERFORATED -> {
                    dent(state, slat.contact(), 1.0f);
                    changed = true;
                    scale *= slat.penetrationScale();
                    lead.cut(slat.penetrationScale());
                }
                case DUD -> {
                    dent(state, slat.lower(), DUD_BEND);
                    dent(state, slat.upper(), DUD_BEND);
                    level.playSound(null, hit.point().x, hit.point().y, hit.point().z, SoundEvents.ANVIL_LAND,
                            SoundSource.HOSTILE, 1.2f, 1.4f);
                    return finish(layer, true, new Result(Verdict.DUD, null, null, DEAD, 0));
                }
                case BURST -> {
                    if (slat.contact() >= 0) {
                        dent(state, slat.contact(), 1.0f);
                        dent(state, slat.contact() - 1, BURST_NEIGHBOUR_BEND);
                        dent(state, slat.contact() + 1, BURST_NEIGHBOUR_BEND);
                    } else {
                        dent(state, slat.lower(), 1.0f);
                        dent(state, slat.upper(), 1.0f);
                    }
                    changed = true;
                    burst = hit.point();
                    scale *= slat.penetrationScale();
                    lead.cut(slat.penetrationScale());
                    if (hull != null) {
                        scale *= AddonPhysics.standoffScale(threat.kind(), hull.point().distanceTo(burst));
                        lead.gap(hull.point().distanceTo(burst));
                    }
                }
            }
            if (burst != null && (threat.kind() == ThreatKind.HE || threat.kind() == ThreatKind.HESH)) {
                break;
            }
        }

        Vec3 hullPoint = hull == null ? null : hull.point();
        float damageScale = threat.penetrationMm() > 0 ? 1.0f : scale;
        Result result;
        if (burst != null) {
            boolean jet = threat.kind().isShapedCharge() && hullPoint != null;
            if (!jet) {
                lead.cut(0.0f);
            }
            result = new Result(Verdict.BURST, burst, jet ? hullPoint : null, lead, jet ? damageScale : 0);
        } else if (hullPoint == null) {
            result = new Result(Verdict.MISSED, null, null, lead, damageScale);
        } else {
            result = new Result(Verdict.THROUGH, null, hullPoint, lead, damageScale);
        }
        return finish(layer, changed, result);
    }

    /** Spends brick {@code cell} of a mounted panel: its blast outward, then one hop of sympathetic neighbours. */
    private static void detonate(AbstractVehicle vehicle, AddonArmorLayer layer, AddonMountState state, int mountIndex,
                                 int cell, EraType type, ServerLevel level) {
        AddonMount mount = layer.mounts().get(mountIndex);
        AddonArmorLayer.Pose pose = layer.pose(vehicle, mountIndex);
        state.live &= ~(1L << cell);
        brick(vehicle, pose, mount, cell, type, level);
        if (type.sympathetic() <= 0) {
            return;
        }
        int column = cell % mount.columns;
        int row = cell / mount.columns;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int c = column + dc;
                int r = row + dr;
                if ((dc == 0 && dr == 0) || c < 0 || c >= mount.columns || r < 0 || r >= mount.rows) {
                    continue;
                }
                int neighbour = r * mount.columns + c;
                if (state.isLive(neighbour) && level.getRandom().nextFloat() < type.sympathetic()) {
                    state.live &= ~(1L << neighbour);
                    brick(vehicle, pose, mount, neighbour, type, level);
                }
            }
        }
    }

    private static void brick(AbstractVehicle vehicle, AddonArmorLayer.Pose pose, AddonMount mount, int cell,
                              EraType type, ServerLevel level) {
        Vec3 centre = mount.cellCentre(cell);
        Vec3 at = pose.toWorld((float) centre.x, (float) centre.y, type.thickness());
        Vec3 inward = pose.toWorld(0, 0, -1).subtract(pose.centre()).normalize();
        EraBlast.detonate(level, at, type, EraBlast.backing(vehicle, at, inward));
    }

    private static Result stopped(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.05, 0.05, 0.05, 0.2);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 0.4f, 2.0f);
        return new Result(Verdict.STOPPED, null, null, DEAD, 0);
    }

    private static Result finish(AddonArmorLayer layer, boolean changed, Result result) {
        if (changed) {
            layer.changed();
        }
        return result;
    }

    private static void dent(AddonMountState state, int bar, float amount) {
        if (bar >= 0 && bar < state.bars.length) {
            state.bars[bar] = Mth.clamp(state.bars[bar] - amount, 0, 1);
        }
    }

    /**
     * Exposure of the hull to a blast: {@link BlastRays}' one ray pass, panels bent on the way.
     *
     * @param shape running AEF blast; null = vanilla sphere
     * @return share of hull-bound blast left after panels; 1 = none installed or no ray met the hull
     */
    public static float blast(AbstractVehicle vehicle, Vec3 centre, float size, @Nullable ExplosionAEF shape) {
        return BlastRays.cast(vehicle, centre, size, shape).exposure;
    }

    /**
     * Blast pass panel stage; null = no panel installed. Every ray carries {@code 4pi/N} of the blast.
     *
     * @param fragments the blast's fragment set: live bricks the rays land on go off ({@link Panels#finish}); null = none
     */
    @Nullable
    public static Panels panels(AbstractVehicle vehicle, float size, @Nullable BlastPayload.Fragments fragments) {
        AddonArmorLayer layer = vehicle.addonArmor();
        return !layer.anyInstalled() || size <= 0 ? null : new Panels(vehicle, layer, size, fragments);
    }

    /**
     * Fluence ~ 1/d^2 from ray density alone. First surface consumes: hull, live ERA brick (insensitive, shields).
     * Slat bar takes its projected cover {@code (bar + depth*tan) / pitch} of the ray and passes the rest.
     * Fragments: each ray carries {@code count / RAYS} of them; a brick whose landed fragments, each of energy
     * {@code >= fragment_initiation_j} after flight, add up to 1 goes off.
     */
    public static final class Panels {

        private final AbstractVehicle vehicle;
        private final AddonArmorLayer layer;
        private final List<AddonMount> mounts;
        private final AddonArmorLayer.Pose[] poses;
        private final Quaternionf[] inverse;
        private final float[][] bend;
        private final float perRay;
        private final Vector3f local = new Vector3f();
        @Nullable
        private final BlastPayload.Fragments fragments;
        private final float reach;
        @Nullable
        private final float[][] struck;

        private Panels(AbstractVehicle vehicle, AddonArmorLayer layer, float size,
                       @Nullable BlastPayload.Fragments fragments) {
            this.fragments = fragments;
            this.reach = size * 2.0f;
            this.struck = fragments == null ? null : new float[layer.mounts().size()][];
            this.vehicle = vehicle;
            this.layer = layer;
            this.mounts = layer.mounts();
            this.poses = layer.poses(vehicle);
            this.inverse = new Quaternionf[poses.length];
            this.bend = new float[poses.length][];
            for (int i = 0; i < poses.length; i++) {
                inverse[i] = new Quaternionf(poses[i].rotation()).conjugate();
                bend[i] = new float[mounts.get(i).bars];
                if (struck != null && mounts.get(i).info.kind == AddonKind.ERA) {
                    struck[i] = new float[mounts.get(i).cells()];
                }
            }
            this.perRay = BLAST_BEND * size * (float) (4 * Math.PI / BlastRays.RAYS);
        }

        /** @param hullFraction where the ray meets the hull, share of {@code reach}; 1 = not at all */
        public float ray(Vec3 centre, Vec3 end, float dx, float dy, float dz, double hullFraction) {
            float power = 1;
            for (AddonArmorLayer.Hit hit : layer.trace(vehicle, centre, end, true, poses)) {
                if (hit.fraction() > hullFraction || power <= 0) {
                    break;
                }
                AddonMount mount = mounts.get(hit.mount());
                AddonMountState state = layer.state(hit.mount());
                if (state.fit.kind == AddonKind.ERA) {
                    int cell = mount.cellAt(hit.x(), hit.y());
                    if (state.isLive(cell)) {
                        if (struck != null && struck[hit.mount()] != null && fragmentJoules(hit.fraction())
                                >= state.fit.era().fragmentInitiationJ()) {
                            struck[hit.mount()][cell] += fragments.count() / (float) BlastRays.RAYS;
                        }
                        power = 0;
                    }
                    continue;
                }
                int k = mount.barAt(hit.y());
                if (state.bars[k] <= 0) {
                    continue;
                }
                inverse[hit.mount()].transform(local.set(dx, dy, dz));
                float cover = Math.min(1, (AddonMount.SLAT_BAR
                        + AddonMount.SLAT_DEPTH * Math.abs(local.y) / Math.max(Math.abs(local.z), 1.0e-3f))
                        / mount.barPitch());
                bend[hit.mount()][k] += power * cover * perRay * BlastRays.falloff(hit.fraction())
                        / (mount.barPitch() * mount.info.width);
                power *= 1 - cover;
            }
            return power;
        }

        /** Energy of one fragment after {@code fraction} of the reach, J. */
        private float fragmentJoules(double fraction) {
            float v = fragments.speed() * (float) Math.exp(-fraction * reach / VehicleBlastResolver.FRAGMENT_DECAY);
            return 0.5f * fragments.massG() / 1000.0f * v * v;
        }

        public void finish() {
            boolean changed = false;
            if (struck != null && vehicle.level() instanceof ServerLevel level) {
                for (int i = 0; i < struck.length; i++) {
                    AddonMountState state = layer.state(i);
                    for (int cell = 0; struck[i] != null && cell < struck[i].length; cell++) {
                        if (struck[i][cell] >= 1.0f && state.isLive(cell)) {
                            detonate(vehicle, layer, state, i, cell, state.fit.era(), level);
                            changed = true;
                        }
                    }
                }
            }
            for (int i = 0; i < bend.length; i++) {
                AddonMountState state = layer.state(i);
                for (int k = 0; k < bend[i].length; k++) {
                    if (bend[i][k] >= MIN_BLAST_BEND && state.bars[k] > 0) {
                        state.bars[k] = Mth.clamp(state.bars[k] - bend[i][k], 0, 1);
                        changed = true;
                    }
                }
            }
            if (changed) {
                layer.changed();
            }
        }
    }
}
