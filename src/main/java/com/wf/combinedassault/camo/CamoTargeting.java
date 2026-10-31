package com.wf.combinedassault.camo;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;

/**
 * Vehicle under crosshair, hull-based rather than vanilla's entity pick. Vanilla only resolves an
 * entity within interaction range (3 blocks, 5 in creative); the camo kit reaches further, so
 * past that range vanilla resolves to the block behind the hull and the kit's own block sampling
 * would eat clicks meant for the vehicle. Must run identically on both sides: sampling writes the
 * held stack, so a client-only skip would desync the two copies of it.
 */
public final class CamoTargeting {

    /** Blocks; matches the decoration item's reach. */
    public static final double REACH = 8;

    private CamoTargeting() {
    }

    public static Vec3 rayStart(Player player) {
        return player.getEyePosition();
    }

    public static Vec3 rayEnd(Player player) {
        return player.getEyePosition().add(player.getLookAngle().scale(REACH));
    }

    /** Nearest vehicle whose hull the ray actually enters, or null. */
    public static AbstractVehicle underRay(Level level, Vec3 start, Vec3 end) {
        AbstractVehicle nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : level.getEntitiesOfClass(AbstractVehicle.class, new AABB(start, end).inflate(2))) {
            VectorUtil.HitBone hit = VectorUtil.hitHull(vehicle, start, end, CamoPlacement.PLATE_NORMAL);
            if (hit == null) {
                continue;
            }
            double distance = hit.position().distanceToSqr(start);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = vehicle;
            }
        }
        return nearest;
    }

    /** Where the player is pointing, hull first. */
    public static AbstractVehicle underCrosshair(Player player) {
        return underRay(player.level(), rayStart(player), rayEnd(player));
    }
}
