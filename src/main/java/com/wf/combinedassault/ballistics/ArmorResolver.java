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

/** Thickness-met-at-incidence armour model; replaces stock's line-to-hull-center distance approximation. */
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

    @Override
    @Nullable
    public BallisticResult resolve(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos,
                                   @Nullable PartUnit<?> hitPart, float penetration, float incomingDamage) {
        ArmorProfile profile = vehicle.armorProfile;
        if (profile == null || !profile.isDefined() || penetration <= 0 || direction.lengthSqr() == 0) {
            return null;
        }
        Vec3 shotDir = direction.normalize();
        Vec3 end = hitPos.add(shotDir.scale(0.1));
        Vector3f worldNormal;
        float nominal;
        Vec3 struck;
        if (vehicle.hits() != null) {
            // hit.armor() from an armor_<mm> material on the struck face; else per-face nominal.
            VehicleHits.Hit hit = vehicle.hits().surface(start, end);
            if (hit == null) {
                return null;
            }
            worldNormal = hit.normal();
            struck = hit.point();
            ArmorFace face = ArmorFace.of(toVehicleFrame(vehicle, worldNormal));
            nominal = hit.armor() > 0 ? hit.armor() : profile.nominal(face);
        } else {
            VectorUtil.HitOBB hit = VectorUtil.hitOBB(vehicle.getOBBs(), start, end);
            if (hit == null) {
                return null;
            }
            worldNormal = VectorUtil.faceNormal(hit.obb(), hit.hitPos());
            struck = hit.hitPos();
            nominal = profile.nominal(ArmorFace.of(toVehicleFrame(vehicle, worldNormal)));
        }
        if (nominal <= 0) {
            return null;
        }

        float wear = vehicle.armorRegions().thicknessScale(ArmorRegions.region(vehicle, struck, worldNormal),
                profile.wearFloor);
        float cos = (float) -shotDir.dot(new Vec3(worldNormal));
        if (cos <= 0) {
            // cos<=0: grazed an edge, named face's normal points away from the shot. Clamp to
            // MIN_COS rather than produce a negative/infinite effective thickness.
            cos = MIN_COS;
        }
        cos = Math.max(cos, MIN_COS);
        float effective = nominal * wear / cos;

        Vec3 normal = new Vec3(worldNormal);
        float incidence = (float) Math.toDegrees(Math.acos(Mth.clamp(cos, -1, 1)));
        boolean overmatch = penetration >= effective * OVERMATCH_RATIO;
        if (incidence > profile.ricochetAngle && !overmatch) {
            return new BallisticResult(HitOutcome.RICOCHET, 0, 0, nominal, effective, penetration, normal);
        }

        float ratio = penetration / effective;
        if (ratio < ABSORB_RATIO) {
            float spall = incomingDamage * ABSORBED_SPALL * ratio / ABSORB_RATIO;
            return new BallisticResult(HitOutcome.ABSORBED, spall, spall, nominal, effective, penetration, normal);
        }
        if (ratio < 1.0f) {
            float t = (ratio - ABSORB_RATIO) / (1.0f - ABSORB_RATIO);
            float scale = Mth.lerp(t, ABSORBED_SPALL, PARTIAL_FLOOR);
            return new BallisticResult(HitOutcome.PARTIAL, incomingDamage * scale, incomingDamage * scale,
                    nominal, effective, penetration, normal);
        }
        if (ratio >= OVERMATCH_RATIO) {
            return new BallisticResult(HitOutcome.OVERMATCH, incomingDamage, incomingDamage,
                    nominal, effective, penetration, normal);
        }
        float t = (ratio - 1.0f) / (OVERMATCH_RATIO - 1.0f);
        float scale = Mth.lerp(t, PARTIAL_FLOOR, 1.0f);
        return new BallisticResult(HitOutcome.PENETRATION, incomingDamage * scale, incomingDamage * scale,
                nominal, effective, penetration, normal);
    }

    /** World normal -> vehicle-local frame; front/side/rear are frame-relative. */
    private static Vector3f toVehicleFrame(AbstractVehicle vehicle, Vector3f worldNormal) {
        Vector3f local = new Vector3f(worldNormal);
        new Quaternionf(vehicle.rotYXZ()).conjugate().transform(local);
        return local;
    }

}
