package com.wf.combinedassault.ballistics;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.util.OBB;

/** Hull-box-face armour model; replaces stock's line-to-hull-center distance approximation. */
public class ArmorResolver implements BallisticResolver {

    /** Past this ratio: full payload, plate irrelevant. */
    private static final float OVERMATCH_RATIO = 1.5f;
    /** Under this ratio: fully stopped. */
    private static final float ABSORB_RATIO = 0.75f;
    /** Payload fraction kept at ratio=1.0 (bare penetration); ramps to 1.0 at OVERMATCH_RATIO. */
    private static final float PARTIAL_FLOOR = 0.35f;
    /** Spall fraction at ABSORB_RATIO; scales by ratio/ABSORB_RATIO below (rifle vs MBT => ~0). */
    private static final float ABSORBED_SPALL = 0.06f;
    /** cos(incidence) floor; avoids divide-near-zero at grazing hits. */
    private static final float MIN_COS = 0.05f;
    /** Metres the main box grows by for {@link #entryFace}: mesh skin (skirts, fenders) sits just outside it. */
    private static final double ENTRY_MARGIN = 0.25;

    @Override
    @Nullable
    public BallisticResult resolve(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos,
                                   @Nullable PartUnit<?> hitPart, float penetration, float incomingDamage,
                                   ThreatKind kind) {
        ArmorProfile profile = vehicle.armorProfile;
        if (profile == null || !profile.isDefined() || penetration <= 0 || direction.lengthSqr() == 0) {
            return null;
        }
        Vec3 shotDir = direction.normalize();
        Plate plate = plate(vehicle, profile, start, hitPos.add(shotDir.scale(0.1)), shotDir, kind.isShapedCharge());
        if (plate == null) {
            return null;
        }
        float nominal = plate.nominal();
        float effective = plate.effective();
        Vec3 normal = new Vec3(plate.normal());
        Vec3 face = new Vec3(plate.faceNormal());
        boolean overmatch = penetration >= effective * OVERMATCH_RATIO;
        if (plate.surfaceIncidence() > profile.ricochetAngle && !overmatch) {
            return new BallisticResult(HitOutcome.RICOCHET, 0, 0, nominal, effective, penetration, normal, face);
        }

        float ratio = penetration / effective;
        if (ratio < ABSORB_RATIO) {
            float spall = incomingDamage * ABSORBED_SPALL * ratio / ABSORB_RATIO;
            return new BallisticResult(HitOutcome.ABSORBED, spall, spall, nominal, effective, penetration, normal,
                    face);
        }
        if (ratio < 1.0f) {
            float t = (ratio - ABSORB_RATIO) / (1.0f - ABSORB_RATIO);
            float scale = Mth.lerp(t, ABSORBED_SPALL, PARTIAL_FLOOR);
            return new BallisticResult(HitOutcome.PARTIAL, incomingDamage * scale, incomingDamage * scale,
                    nominal, effective, penetration, normal, face);
        }
        if (ratio >= OVERMATCH_RATIO) {
            return new BallisticResult(HitOutcome.OVERMATCH, incomingDamage, incomingDamage,
                    nominal, effective, penetration, normal, face);
        }
        float t = (ratio - 1.0f) / (OVERMATCH_RATIO - 1.0f);
        float scale = Mth.lerp(t, PARTIAL_FLOOR, 1.0f);
        return new BallisticResult(HitOutcome.PENETRATION, incomingDamage * scale, incomingDamage * scale,
                nominal, effective, penetration, normal, face);
    }

    /**
     * Plate met along {@code start -> end}. Profile values = RHAe (slope included) => face and {@code cos} from the
     * main box face the shot entered ({@link #entryFace}), else the shot's aspect (dominant vehicle axis of
     * {@code -shotDir}); a part with its own faces: aspect in its rest frame. Mesh slope counts only for ricochet
     * ({@code surfaceCos}) and real plates ({@code armor_<mm>}, plate zones: {@code cos = surfaceCos}).
     *
     * @param normal     struck surface, world frame; toward the shooter from a trace
     * @param faceNormal {@code face} axis, outward, world frame
     * @param part       part with its own faces, else null (hull)
     * @param zone       matched zone name, else null
     * @param surfaceCos clamped to {@link #MIN_COS}
     */
    public record Plate(Vec3 point, Vector3f normal, ArmorFace face, Vector3f faceNormal, @Nullable String part,
                        @Nullable String zone, float nominal, boolean plate, float wear, float cos,
                        float surfaceCos) {
        public float effective() {
            return nominal * wear / cos;
        }

        public float incidence() {
            return degrees(cos);
        }

        public float surfaceIncidence() {
            return degrees(surfaceCos);
        }

        private static float degrees(float cos) {
            return (float) Math.toDegrees(Math.acos(Mth.clamp(cos, -1, 1)));
        }
    }

    /** Null: missed, or plate {@code <= 0} mm. */
    @Nullable
    public static Plate plate(AbstractVehicle vehicle, ArmorProfile profile, Vec3 start, Vec3 end, Vec3 shotDir,
                              boolean shapedCharge) {
        if (vehicle.hits() != null) {
            VehicleHits.Hit hit = vehicle.hits().surface(start, end);
            return hit == null ? null
                    : plate(vehicle, profile, hit.node(), hit.point(), hit.normal(), hit.armor(), shotDir, shapedCharge);
        }
        VectorUtil.HitOBB hit = VectorUtil.hitOBB(vehicle.getOBBs(), start, end);
        return hit == null ? null : plate(vehicle, profile, -1, hit.hitPos(),
                VectorUtil.faceNormal(hit.obb(), hit.hitPos()), 0, shotDir, shapedCharge);
    }

    /**
     * Plate at a known strike.
     *
     * @param node        hit node, -1 = structure boxes
     * @param worldNormal struck surface, either sign
     * @param armor       {@code armor_<mm>} of the struck face, 0 = none
     */
    @Nullable
    public static Plate plate(AbstractVehicle vehicle, ArmorProfile profile, int node, Vec3 struck,
                              Vector3f worldNormal, float armor, Vec3 shotDir, boolean shapedCharge) {
        VehicleHits hits = vehicle.hits();
        String part = null;
        ArmorFaces faces = profile;
        if (node >= 0) {
            String owner = hits.model().nodes().get(node).part();
            if (owner != null && profile.parts.containsKey(owner)) {
                part = owner;
                faces = profile.parts.get(owner);
            }
        }
        Vector3f axis = new Vector3f();
        Vector3f faceNormal = new Vector3f();
        if (part != null) {
            axis = aspectAxis(hits.restDirection(node, shotDir.reverse().toVector3f()));
            faceNormal = hits.worldDirection(node, axis);
        } else if (!entryFace(vehicle, struck, shotDir, axis, faceNormal)) {
            axis = aspectAxis(toVehicleFrame(vehicle, shotDir.reverse().toVector3f()));
            faceNormal = vehicle.rotYXZ().transform(new Vector3f(axis));
        }
        ArmorFace face = ArmorFace.of(axis);
        ArmorProfile.Zone zone = null;
        if (node >= 0 && !profile.zones.isEmpty()) {
            Vector3f at = hits.restPoint(node, struck);
            for (ArmorProfile.Zone z : profile.zones) {
                if (z.matches(part, face, at.x, at.y, at.z)) {
                    zone = z;
                    break;
                }
            }
        }
        boolean plate = armor > 0 || zone != null && zone.plate();
        float nominal = armor > 0 ? armor
                : zone != null ? zone.nominal(shapedCharge) : faces.nominal(face, shapedCharge);
        if (nominal <= 0) {
            return null;
        }
        float wear = vehicle.armorRegions().thicknessScale(ArmorRegions.region(vehicle, struck, faceNormal),
                profile.wearFloor);
        float surfaceCos = Math.max((float) Math.abs(shotDir.dot(new Vec3(worldNormal))), MIN_COS);
        float faceCos = Math.max((float) -shotDir.dot(new Vec3(faceNormal)), MIN_COS);
        return new Plate(struck, worldNormal, face, faceNormal, part, zone == null ? null : zone.name(), nominal,
                plate, wear, plate ? surfaceCos : faceCos, surfaceCos);
    }

    /**
     * Main box face the shot line entered through before {@code struck}; {@code struck} outside the box grown by
     * {@link #ENTRY_MARGIN} (turret, fittings above it) => false.
     *
     * @param axis       out: outward face axis, box frame (= vehicle frame)
     * @param faceNormal out: same, world frame
     */
    private static boolean entryFace(AbstractVehicle vehicle, Vec3 struck, Vec3 shotDir, Vector3f axis,
                                     Vector3f faceNormal) {
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        if (main == null) {
            return false;
        }
        OBB box = main.obb();
        Vector3f[] axes = box.getAxes();
        Vector3f ext = box.extents();
        double rx = struck.x - box.centerX(), ry = struck.y - box.centerY(), rz = struck.z - box.centerZ();
        int best = -1;
        double bestT = Double.NEGATIVE_INFINITY;
        float sign = 0;
        for (int i = 0; i < 3; i++) {
            Vector3f a = axes[i];
            double p = rx * a.x + ry * a.y + rz * a.z;
            double d = shotDir.x * a.x + shotDir.y * a.y + shotDir.z * a.z;
            double e = ext.get(i) + ENTRY_MARGIN;
            if (Math.abs(p) > e) {
                return false;
            }
            if (Math.abs(d) < 1.0e-6) {
                continue;
            }
            double t = ((d > 0 ? -e : e) - p) / d;
            if (t > bestT) {
                bestT = t;
                best = i;
                sign = d > 0 ? -1 : 1;
            }
        }
        if (best < 0) {
            return false;
        }
        axis.setComponent(best, sign);
        faceNormal.set(axes[best]).mul(sign);
        return true;
    }

    /** Signed unit vehicle axis along {@code local}'s largest component; ties as {@link ArmorFace#of}. */
    public static Vector3f aspectAxis(Vector3f local) {
        float ax = Math.abs(local.x), ay = Math.abs(local.y), az = Math.abs(local.z);
        if (ay >= ax && ay >= az) {
            return new Vector3f(0, local.y >= 0 ? 1 : -1, 0);
        }
        if (az >= ax) {
            return new Vector3f(0, 0, local.z >= 0 ? 1 : -1);
        }
        return new Vector3f(local.x >= 0 ? 1 : -1, 0, 0);
    }

    /** World normal -> vehicle-local frame; front/side/rear are frame-relative. */
    public static Vector3f toVehicleFrame(AbstractVehicle vehicle, Vector3f worldNormal) {
        Vector3f local = new Vector3f(worldNormal);
        new Quaternionf(vehicle.rotYXZ()).conjugate().transform(local);
        return local;
    }

}
