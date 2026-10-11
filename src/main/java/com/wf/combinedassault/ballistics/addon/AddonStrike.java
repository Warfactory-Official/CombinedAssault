package com.wf.combinedassault.ballistics.addon;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatKind;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.wflib.util.OBB;

import java.util.List;

/** A round's path through a vehicle's ERA and slat, applied: bricks spent, bars bent. Server side. */
public final class AddonStrike {

    /** Past the tick's own travel: a panel's gap to the hull, generously. */
    private static final double HULL_REACH = 6.0;
    private static final float ERA_BLAST_RADIUS = 1.2f;
    private static final float BURST_NEIGHBOUR_BEND = 0.5f;
    private static final float DUD_BEND = 0.35f;
    private static final float STOP_DENT = 0.1f;
    private static final float MIN_BLAST_BEND = 0.05f;
    private static final int BLAST_RAYS = 1024;
    private static final float[] BLAST_DIRS = fibonacci(BLAST_RAYS);
    /** Size-4 blast 1 block off a face-on bar => bend ~1. */
    private static final float BLAST_BEND = 2.0f;
    /** Bounding box -> panels: slat standoff past the hull box. */
    private static final double PANEL_MARGIN = 1.5;

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
     * @param damageScale for rounds without an armour-model penetration figure, the same cut applied to damage
     */
    public record Result(Verdict verdict, @Nullable Vec3 burst, @Nullable Vec3 hull, float penetrationScale,
                         float damageScale) {

        static final Result CLEAR = new Result(Verdict.CLEAR, null, null, 1, 1);
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
        VectorUtil.HitOBB hull = VectorUtil.hitOBB(vehicle.getOBBs(), start, end);
        double hullFraction = hull == null ? 1.0
                : (hull.distance() + AddonArmorLayer.SKIN_INSET) / (speed + HULL_REACH);
        List<AddonArmorLayer.Hit> hits = layer.trace(vehicle, start, end, true);
        hits.removeIf(hit -> hit.fraction() > hullFraction);
        if (hits.isEmpty()) {
            return Result.CLEAR;
        }

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
                AddonPhysics.EraOutcome era = AddonPhysics.era(state.fit.era, threat, hit.cos(),
                        mount.cellEdgeFraction(hit.x(), hit.y()));
                if (era.initiated()) {
                    state.live &= ~(1L << cell);
                    changed = true;
                    Vec3 at = layer.pose(vehicle, hit.mount()).toWorld(
                            (float) mount.cellCentre(cell).x, (float) mount.cellCentre(cell).y,
                            state.fit.era.thickness());
                    level.explode(null, at.x, at.y, at.z, ERA_BLAST_RADIUS, Level.ExplosionInteraction.NONE);
                    scale *= era.penetrationScale();
                }
                if (burst == null) {
                    switch (threat.kind()) {
                        case HEAT, TANDEM_HEAT, HE -> burst = hit.point();
                        case SMALL_ARMS -> {
                            return finish(layer, changed, stopped(level, hit.point()));
                        }
                        case KINETIC -> {
                        }
                    }
                    if (threat.kind() == ThreatKind.HE) {
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
                }
                case DUD -> {
                    dent(state, slat.lower(), DUD_BEND);
                    dent(state, slat.upper(), DUD_BEND);
                    level.playSound(null, hit.point().x, hit.point().y, hit.point().z, SoundEvents.ANVIL_LAND,
                            SoundSource.HOSTILE, 1.2f, 1.4f);
                    return finish(layer, true, new Result(Verdict.DUD, null, null, 0, 0));
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
                    if (hull != null) {
                        scale *= AddonPhysics.standoffScale(threat.kind(), hull.hitPos().distanceTo(burst));
                    }
                }
            }
            if (burst != null && threat.kind() == ThreatKind.HE) {
                break;
            }
        }

        Vec3 hullPoint = hull == null ? null : hull.hitPos();
        float damageScale = threat.penetrationMm() > 0 ? 1.0f : scale;
        Result result;
        if (burst != null) {
            boolean jet = threat.kind().isShapedCharge() && hullPoint != null;
            result = new Result(Verdict.BURST, burst, jet ? hullPoint : null, jet ? scale : 0, jet ? damageScale : 0);
        } else if (hullPoint == null) {
            result = new Result(Verdict.MISSED, null, null, scale, damageScale);
        } else {
            result = new Result(Verdict.THROUGH, null, hullPoint, scale, damageScale);
        }
        return finish(layer, changed, result);
    }

    private static Result stopped(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.05, 0.05, 0.05, 0.2);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 0.4f, 2.0f);
        return new Result(Verdict.STOPPED, null, null, 0, 0);
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
     * Ray-cast; every ray carries {@code 4pi/N} of the blast => fluence ~ 1/d^2 from ray density alone. First
     * surface consumes: hull, live ERA brick (insensitive, shields). Slat bar takes its projected cover
     * {@code (bar + depth*tan) / pitch} of the ray and passes the rest.
     *
     * @param shape running AEF blast; null = vanilla sphere
     * @return share of hull-bound blast left after panels; 1 = none installed or no ray met the hull
     */
    public static float blast(AbstractVehicle vehicle, Vec3 centre, float size, @Nullable ExplosionAEF shape) {
        AddonArmorLayer layer = vehicle.addonArmor();
        if (!layer.anyInstalled() || size <= 0) {
            return 1;
        }
        double reach = size * 2.0;
        AABB box = vehicle.getBoundingBox().inflate(PANEL_MARGIN);
        Vec3 toBox = box.getCenter().subtract(centre);
        double distance = toBox.length();
        double bound = 0.5 * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                + box.getZsize() * box.getZsize());
        if (distance - bound > reach) {
            return 1;
        }
        boolean inside = box.contains(centre);
        double cosCone = distance <= bound ? -1 : Math.cos(Math.asin(bound / distance));
        Vec3 axis = distance < 1.0e-6 ? Vec3.ZERO : toBox.scale(1 / distance);
        List<AddonMount> mounts = layer.mounts();
        AddonArmorLayer.Pose[] poses = layer.poses(vehicle);
        Quaternionf[] inverse = new Quaternionf[poses.length];
        float[][] bend = new float[poses.length][];
        for (int i = 0; i < poses.length; i++) {
            inverse[i] = new Quaternionf(poses[i].rotation()).conjugate();
            bend[i] = new float[mounts.get(i).bars];
        }
        float perRay = BLAST_BEND * size * (float) (4 * Math.PI / BLAST_RAYS);
        List<OBB> hullBoxes = vehicle.getOBBs();
        Vector3f local = new Vector3f();
        double hullAll = 0;
        double hullOpen = 0;
        for (int r = 0; r < BLAST_RAYS; r++) {
            float dx = BLAST_DIRS[3 * r];
            float dy = BLAST_DIRS[3 * r + 1];
            float dz = BLAST_DIRS[3 * r + 2];
            if (dx * axis.x + dy * axis.y + dz * axis.z < cosCone) {
                continue;
            }
            Vec3 end = centre.add(dx * reach, dy * reach, dz * reach);
            if (!inside && box.clip(centre, end).isEmpty()
                    || shape != null && !shape.inBlastShape(centre.add(dx, dy, dz))) {
                continue;
            }
            VectorUtil.HitOBB hull = VectorUtil.hitOBB(hullBoxes, centre, end);
            double hullFraction = hull == null ? 1.0 : (hull.distance() + AddonArmorLayer.SKIN_INSET) / reach;
            float power = 1;
            for (AddonArmorLayer.Hit hit : layer.trace(vehicle, centre, end, true, poses)) {
                if (hit.fraction() > hullFraction || power <= 0) {
                    break;
                }
                AddonMount mount = mounts.get(hit.mount());
                AddonMountState state = layer.state(hit.mount());
                if (state.fit.kind == AddonKind.ERA) {
                    if (state.isLive(mount.cellAt(hit.x(), hit.y()))) {
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
                bend[hit.mount()][k] += power * cover * perRay * falloff(hit.fraction())
                        / (mount.barPitch() * mount.info.width);
                power *= 1 - cover;
            }
            if (hull != null) {
                float falloff = falloff(hull.distance() / reach);
                hullAll += falloff;
                hullOpen += power * falloff;
            }
        }
        boolean changed = false;
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
        return hullAll <= 0 ? 1 : (float) (hullOpen / hullAll);
    }

    private static float falloff(double fraction) {
        float f = (float) Math.max(0, 1 - fraction);
        return f * f;
    }

    private static float[] fibonacci(int n) {
        float[] dirs = new float[3 * n];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) * 2.0 / n;
            double ring = Math.sqrt(1 - y * y);
            double phi = golden * i;
            dirs[3 * i] = (float) (Math.cos(phi) * ring);
            dirs[3 * i + 1] = (float) y;
            dirs[3 * i + 2] = (float) (Math.sin(phi) * ring);
        }
        return dirs;
    }
}
