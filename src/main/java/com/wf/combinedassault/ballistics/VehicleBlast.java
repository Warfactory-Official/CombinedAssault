package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Nearest hull point to a blast: a carrier-side torpedo reads 30 blocks off the entity position. */
public final class VehicleBlast {

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

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

}
