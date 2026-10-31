package com.wf.combinedassault.uav.fpv;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

/**
 * Impact detected from outside the vehicle, not the host's collision solver (solver stays explosion-agnostic).
 * Runs after the solver has already zeroed velocity against the wall => evidence is distance travelled
 * (last tick's position to now, plus a short forward probe), never remaining velocity.
 */
public final class FpvImpact {

    /** Entity-sweep radius, blocks. */
    private static final double IMPACT_RADIUS = 0.45D;

    /** Forward probe past a wall the solver has already stopped the drone against, blocks. */
    private static final double IMPACT_PROBE = 0.6D;

    /** What was hit, and how hard. A block hit reports a null entity. */
    public record Contact(@Nullable Entity entity, Vec3 at, double closingSpeed) {
    }

    private FpvImpact() {
    }

    /**
     * @param from      drone position at the end of last tick
     * @param lastSpeed blocks moved on the previous tick
     * @return the contact, or null for a clean tick
     */
    @Nullable
    public static Contact detect(AbstractVehicle drone, Vec3 from, double lastSpeed) {
        Level level = drone.level();
        Vec3 to = drone.position();
        Vec3 travelled = to.subtract(from);
        // Stop tick's own displacement ~0; lastSpeed carried forward is what registers the impact at all.
        double speed = Math.max(travelled.length(), lastSpeed);
        if (speed <= 0) {
            return null;
        }

        Entity struck = firstEntity(drone, level, from, to);
        if (struck != null) {
            Vec3 relative = drone.getDeltaMovement().subtract(struck.getDeltaMovement());
            return new Contact(struck, struck.position(), Math.max(relative.length(), speed));
        }

        BlockHitResult block = clipAlong(drone, level, from, to);
        if (block == null) {
            Vec3 facing = travelled.lengthSqr() > 1.0E-8 ? travelled.normalize() : drone.getDeltaMovement();
            if (facing.lengthSqr() > 1.0E-8) {
                // Already parked against the wall => own-movement sweep finds nothing; probe ahead instead.
                block = clipAlong(drone, level, to, to.add(facing.normalize().scale(IMPACT_PROBE)));
            }
        }
        if (block != null) {
            return new Contact(null, block.getLocation(), speed);
        }
        return null;
    }

    @Nullable
    private static BlockHitResult clipAlong(AbstractVehicle drone, Level level, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) < 1.0E-8) {
            return null;
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    @Nullable
    private static Entity firstEntity(AbstractVehicle drone, Level level, Vec3 from, Vec3 to) {
        AABB sweep = new AABB(from, to).inflate(IMPACT_RADIUS);
        Entity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Entity candidate : level.getEntities(drone, sweep, e -> isTarget(drone, e))) {
            double distSq = candidate.position().distanceToSqr(to);
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** Impact target; excludes items/projectiles/own riders (arrow chasing it, or its operator, must not end the run). */
    private static boolean isTarget(AbstractVehicle drone, Entity candidate) {
        if (candidate == drone || candidate.isRemoved() || candidate.isSpectator()) {
            return false;
        }
        if (candidate.getVehicle() == drone || drone.hasPassenger(candidate)) {
            return false;
        }
        return !(candidate instanceof ItemEntity) && !(candidate instanceof Projectile);
    }

}
