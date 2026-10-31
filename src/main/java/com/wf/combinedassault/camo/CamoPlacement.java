package com.wf.combinedassault.camo;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.util.VectorUtil;

/**
 * Turns a hull hit into a piece. Shared by player strokes and the seeding command, server-side,
 * from {@link VectorUtil#hitHull}, so the two can't drift in how they read a surface.
 */
public final class CamoPlacement {

    /** Piece facing before alignment; rotated onto the surface normal by the trace. */
    public static final Vec3 PLATE_NORMAL = new Vec3(0, 0, 1);
    /** Degrees off-surface at full jitter. */
    private static final float TILT = 12f;

    private CamoPlacement() {
    }

    public static CamoPiece from(VectorUtil.HitBone hit, int bone, int material, CamoKind kind,
                                 float scale, float jitter, RandomSource random) {
        Vector3f euler = new Vector3f();
        hit.rotation().getEulerAnglesYXZ(euler);
        // Roll free (spin about the surface normal reads as scatter); pitch/yaw jitter only, a
        // random pitch would lift the piece off the hull.
        float roll = (float) Math.toDegrees(euler.z) + Mth.randomBetween(random, -180f, 180f) * jitter;
        float tilt = TILT * jitter;
        return new CamoPiece(bone, material, kind,
                (float) hit.offset().x, (float) hit.offset().y, (float) hit.offset().z,
                scale,
                (float) Math.toDegrees(euler.x) + Mth.randomBetween(random, -tilt, tilt),
                (float) Math.toDegrees(-euler.y) + Mth.randomBetween(random, -tilt, tilt),
                roll);
    }
}
