package com.wf.combinedassault.ballistics;

import net.minecraft.world.phys.Vec3;

/**
 * @param nominalThickness   mm
 * @param effectiveThickness mm, after angle + wear
 * @param penetration        mm
 * @param normal             struck surface, outward, world frame
 * @param faceNormal         armour face by shot aspect, outward, world frame ({@link ArmorResolver.Plate})
 */
public record BallisticResult(HitOutcome outcome, float hullDamage, float partDamage,
                              float nominalThickness, float effectiveThickness, float penetration, Vec3 normal,
                              Vec3 faceNormal) {

    public float ratio() {
        return effectiveThickness <= 0 ? Float.POSITIVE_INFINITY : penetration / effectiveThickness;
    }

}
