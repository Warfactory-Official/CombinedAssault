package com.wf.combinedassault.ballistics.addon;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;

/** The mount point under a player's crosshair, installed or not. Same answer on both sides. */
public final class AddonTargeting {

    public static final double REACH = 5.0;

    public record Target(AbstractVehicle vehicle, int mount, AddonArmorLayer.Hit hit) {
    }

    private AddonTargeting() {
    }

    @Nullable
    public static Target underCrosshair(Player player) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(REACH));
        HitResult block = player.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        Target best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : player.level().getEntitiesOfClass(AbstractVehicle.class,
                new AABB(start, end).inflate(2))) {
            if (vehicle.addonArmor().isEmpty()) {
                continue;
            }
            AddonArmorLayer.Hit hit = vehicle.addonArmor().first(vehicle, start, end, false);
            if (hit == null) {
                continue;
            }
            double distance = hit.point().distanceTo(start);
            Vec3 hull = VectorUtil.closestHullHit(vehicle, start, end);
            if (hull != null && hull.distanceTo(start) + AddonArmorLayer.SKIN_INSET < distance) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Target(vehicle, hit.mount(), hit);
            }
        }
        return best;
    }
}
