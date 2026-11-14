package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.Ballistics;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.wflib.armor.api.hull.HullOutcome;
import com.wf.wflib.armor.api.hull.HullResolver;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One shot at a resting vehicle through the strike path {@code DamageSystem} takes. */
public final class VehicleShots {

    /** Shooter bearing: azimuth 0 ahead, 90 port; elevation above the horizon. */
    public record Ray(Vec3 start, Vec3 dir, Vec3 end, @Nullable VehicleHits.Hit hit, double offset) {
    }

    public record Result(String verdict, String node, String struckPart, String face, String zone, String plate,
                  double nominal, double los, double pen, double ratio, double incidence, double surfaceIncidence,
                  double hullDamage, double partDamage, String partDamaged, String stock, Vec3 local,
                  int walkLayers, int walkDefeated, double interiorPen, double residualPen, int exits) {

        static final Result MISS = new Result("MISS", "", "", "", "", "", 0, 0, 0, 0, 0, 0, 0, 0, "", "", Vec3.ZERO,
                0, 0, 0, 0, 0);
    }

    private VehicleShots() {
    }

    /** Through {@code aim}; a hull aim that finds no surface moves to the nearest node centres, {@code offset} m. */
    public static Ray ray(VehicleRig rig, Vec3 aim, double azimuthDeg, double elevationDeg) {
        double az = Math.toRadians(azimuthDeg), el = Math.toRadians(elevationDeg);
        Vec3 toShooter = new Vec3(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az));
        Vec3 dir = toShooter.reverse();
        Ray first = through(rig, aim, toShooter, dir, 0);
        if (first.hit() != null || !aim.equals(rig.aims.get(0).point())) {
            return first;
        }
        List<Vec3> near = new ArrayList<>(rig.nodeCentres);
        near.sort(java.util.Comparator.comparingDouble(c -> c.subtract(aim).cross(dir).lengthSqr()));
        for (int i = 0; i < Math.min(6, near.size()); i++) {
            Vec3 c = near.get(i);
            Vec3 along = c.subtract(aim);
            Vec3 lateral = along.subtract(dir.scale(along.dot(dir)));
            Ray r = through(rig, aim.add(lateral), toShooter, dir, lateral.length());
            if (r.hit() != null) {
                return r;
            }
        }
        return first;
    }

    private static Ray through(VehicleRig rig, Vec3 aim, Vec3 toShooter, Vec3 dir, double offset) {
        Vec3 start = aim.add(toShooter.scale(rig.reach));
        Vec3 end = start.add(dir.scale(2 * rig.reach));
        return new Ray(start, dir, end, rig.hits.surface(start, end), offset);
    }

    /**
     * @param arrival  blocks/tick on arrival
     * @param threat   at arrival speed
     * @param damage   direct-hit damage before armour
     */
    public static Result shoot(ServerLevel level, VehicleRig rig, Ray ray, Vec3 arrival, Threat threat, float damage) {
        if (ray.hit() == null) {
            return Result.MISS;
        }
        AbstractVehicle vehicle = rig.vehicle;
        Vec3 hitPos = ray.hit().point();
        StrikeSource source = new StrikeSource(level, threat, ray.start(), ray.dir().scale(arrival.length()));
        PartUnit<?> part = VectorUtil.hitPartUnit(vehicle, ray.start(), hitPos.add(ray.dir().scale(0.1)), false);
        String node = rig.hits.model().nodes().get(ray.hit().node()).mesh().name();
        HullResult r = Ballistics.resolve(vehicle, source, damage, hitPos, null);
        Vec3 local = rig.local(hitPos);
        if (r == null) {
            return stock(vehicle, source, part, node, damage, hitPos, local);
        }
        HullResolver.Plate plate = HullResolver.plate(vehicle, vehicle.armorProfile, ray.start(),
                hitPos.add(ray.dir().scale(0.1)), ray.dir().normalize(), threat.kind().isShapedCharge());
        String partDamaged = r.partDamage() > 0 && part != null && !part.isDetached() ? part.getId() : "";
        return new Result(r.outcome().name(), node, part == null ? "" : part.getId(),
                plate == null ? "" : plate.face().name().toLowerCase(java.util.Locale.ROOT),
                plate == null || plate.zone() == null ? "" : plate.zone(), plate == null || plate.plateId() == null ? "" : plate.plateId(),
                r.nominalThickness(), r.effectiveThickness(), r.penetration(), r.ratio(),
                plate == null ? 0 : plate.incidence(), plate == null ? 0 : plate.surfaceIncidence(),
                r.hullDamage(), r.partDamage(), partDamaged, "", local,
                r.walk().layers(), r.walk().defeated(), r.interiorPen(), r.residualPen(), r.exits() ? 1 : 0);
    }

    private static Result stock(AbstractVehicle vehicle, StrikeSource source, @Nullable PartUnit<?> part, String node,
                                float damage, Vec3 hitPos, Vec3 local) {
        float incoming = damage < 0.1 ? 0 : damage;
        double hull;
        double partDamage = 0;
        String partDamaged = "";
        String stock;
        if (part != null && part.isDefensive() && !part.isDetached()) {
            float p = incoming < 0.1 ? 0 : incoming < part.getDefenseStats().damageThreshold ? 0.1f : incoming;
            partDamage = p;
            partDamaged = p > 0 ? part.getId() : "";
            hull = p * part.getDefenseStats().damageTransferCoefficient;
            stock = "part";
        } else if (incoming < vehicle.defenseStats.damageThreshold) {
            hull = 0.1;
            stock = "threshold";
        } else {
            OBB obb = vehicle.getMainCubeOBB().obb();
            Vec3 core = vehicle.relativeRotPos(new Vec3(obb.center()), false);
            Vec3 direction = source.velocity();
            double distanceToCore = core.subtract(hitPos).cross(direction).length() / direction.length();
            double distanceMax = obb.extents().get(obb.extents().maxComponent()) * 2;
            hull = incoming * (distanceMax - distanceToCore) / distanceMax;
            stock = "coreline";
        }
        String verdict = vehicle.armorProfile == null || !vehicle.armorProfile.isDefined() ? "UNARMOURED" : "NO_MODEL";
        return new Result(verdict, node, part == null ? "" : part.getId(), "", "", "", 0, 0,
                source.threat().penetrationMm(), 0, 0, 0, hull, partDamage, partDamaged, stock, local, 0, 0, 0, 0, 0);
    }

    static boolean through(String verdict) {
        return verdict.equals(HullOutcome.PARTIAL.name()) || verdict.equals(HullOutcome.PENETRATION.name())
                || verdict.equals(HullOutcome.OVERMATCH.name());
    }
}
