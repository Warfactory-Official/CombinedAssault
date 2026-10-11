package com.wf.combinedassault.vehicle.camo;

/**
 * One camo piece, as data. Not a {@link com.wf.combinedassault.vehicle.part.PartUnit}: no
 * health/defense/OBB/sync/model, so hundreds of these bake into a handful of draws.
 *
 * @param bone     index into {@link CamoLayer#bone(int)}
 * @param material index into {@link CamoLayer#material(int)}
 * @param x        bone-local offset from pivot
 * @param scale    edge length ({@link CamoKind#PLATE}) or model scale ({@link CamoKind#CUBE})
 * @param pitch    degrees, YXZ order (mod convention)
 */
public record CamoPiece(int bone, int material, CamoKind kind,
                        float x, float y, float z,
                        float scale, float pitch, float yaw, float roll) {
}
