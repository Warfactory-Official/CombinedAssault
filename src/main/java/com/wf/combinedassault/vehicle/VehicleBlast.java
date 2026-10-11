package com.wf.combinedassault.vehicle;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

/**
 * Nearest hull point to a blast, and the hull-area fraction around it the blast has a line to.
 * Vanilla's entity-position + bbox-grid raycast fails on a hull: a carrier-side torpedo reads 30
 * blocks off and the bbox grid runs to ~20M rays.
 */
public final class VehicleBlast {

    private static final float[][] SPREAD = {
            {0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
            {0.7f, 0.7f, 0}, {-0.7f, -0.7f, 0}};
    private static final float SPREAD_BLOCKS = 1.5f;

    private VehicleBlast() {
    }

    /** Nearest hull point to pos; pos itself when there is no hull. */
    public static Vec3 nearestHullPoint(AbstractVehicle vehicle, Vec3 pos) {
        VehicleCubeOBB hull = vehicle.getMainCubeOBB();
        if (hull == null) {
            return vehicle.position();
        }
        OBB obb = hull.obb();
        Vector3f[] axes = obb.getAxes();
        Vector3f local = obb.worldToLocal(pos.x, pos.y, pos.z, axes, new Vector3f());
        Vector3f extents = obb.extents();
        local.x = clamp(local.x, -extents.x, extents.x);
        local.y = clamp(local.y, -extents.y, extents.y);
        local.z = clamp(local.z, -extents.z, extents.z);
        Vector3f world = obb.localToWorld(local, axes, new Vector3f());
        return new Vec3(world.x, world.y, world.z);
    }

    /** Fraction of hull-area sample points around target visible from blast, through blocks. Fluids don't block. */
    public static double seenFraction(Level level, AbstractVehicle vehicle, Vec3 blast, Vec3 target) {
        int seen = 0;
        for (float[] offset : SPREAD) {
            Vec3 point = nearestHullPoint(vehicle, target.add(offset[0] * SPREAD_BLOCKS,
                    offset[1] * SPREAD_BLOCKS, offset[2] * SPREAD_BLOCKS));
            HitResult hit = level.clip(new ClipContext(blast, point, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, vehicle));
            if (hit.getType() == HitResult.Type.MISS) {
                seen++;
            }
        }
        return seen / (double) SPREAD.length;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

}
