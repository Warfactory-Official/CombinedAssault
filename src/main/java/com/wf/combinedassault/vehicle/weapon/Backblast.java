package com.wf.combinedassault.vehicle.weapon;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Weapon json {@code backblast}: cone out of the breech, opposite the bore. Living entities in it with a clear line
 * from the breech take {@code damage} and {@code knockback} x (1 - d / length) x (1 - angle / half_angle); the
 * vehicle and its riders are spared.
 */
public final class Backblast {

    @SerializedName("length")
    public double length = 8;

    @SerializedName("half_angle")
    public double halfAngle = 30;

    @SerializedName("damage")
    public float damage = 12;

    /** Blocks/tick at the breech. */
    @SerializedName("knockback")
    public double knockback = 0.8;

    /** Server, per round fired. Breech = muzzle - bore x {@code barrelLength}. */
    void fire(ServerLevel level, AbstractVehicle vehicle, AimContext aim, float barrelLength, @Nullable Entity shooter) {
        Vec3 bore = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        Vec3 breech = aim.from.subtract(bore.scale(barrelLength));
        Vec3 rear = bore.reverse();
        double cos = Math.cos(Math.toRadians(halfAngle));
        AABB box = new AABB(breech, breech.add(rear.scale(length))).inflate(length * Math.sin(Math.toRadians(halfAngle)));
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (victim.getRootVehicle() == vehicle) {
                continue;
            }
            Vec3 to = victim.getBoundingBox().getCenter().subtract(breech);
            double d = to.length();
            double along = to.dot(rear);
            if (d > length || along <= 0 || along < d * cos) {
                continue;
            }
            if (level.clip(new ClipContext(breech, victim.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, vehicle)).getType() != HitResult.Type.MISS) {
                continue;
            }
            double angle = Math.toDegrees(Math.acos(Math.min(1, along / Math.max(d, 1e-6))));
            double falloff = (1 - d / length) * (1 - angle / halfAngle);
            victim.hurt(level.damageSources().explosion(vehicle, shooter), (float) (damage * falloff));
            victim.push(rear.scale(knockback * falloff));
            victim.hurtMarked = true;
        }
        for (int i = 0; i < 24; i++) {
            double t = level.random.nextDouble();
            Vec3 at = breech.add(rear.scale(t * length * 0.6));
            Vec3 drift = rear.scale(0.25 + 0.35 * (1 - t));
            level.sendParticles(i % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    at.x, at.y, at.z, 0, drift.x, drift.y + 0.02, drift.z, 1);
        }
    }
}
