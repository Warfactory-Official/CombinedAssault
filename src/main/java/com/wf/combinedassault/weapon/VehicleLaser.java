package com.wf.combinedassault.weapon;

import com.wf.combinedassault.weapon.data.VehicleLaserWeaponData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.wflib.laser.api.LaserDamage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Beam, one tick per shot: first entity on the aim line burns (wflib {@link LaserDamage}); no ammo, vehicle energy. */
public class VehicleLaser extends AbstractVehicleWeapon<VehicleLaserWeaponData> {

    public VehicleLaser(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleLaserWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean hasAmmo() {
        return true;
    }

    /** Enough energy for one beam tick. */
    public boolean ready() {
        return vehicle.getEnergy() >= getData().energyPerTick;
    }

    private boolean drawPower() {
        if (!ready()) {
            return false;
        }
        vehicle.setEnergy(vehicle.getEnergy() - getData().energyPerTick);
        return true;
    }

    public double range() {
        return getData().range;
    }

    /** Manual: along the aim; nothing on the line => beam to the block or out to range. */
    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (isCoolingDown() || !(vehicle.level() instanceof ServerLevel level) || !drawPower()) {
            return false;
        }
        AimContext aim = aimContexts.getFirst();
        Vec3 from = aim.from;
        Vec3 end = from.add(VectorUtil.rotToVec(aim.direction.x, aim.direction.y).scale(getData().range));
        BlockHitResult block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, (Entity) null));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, null, from, end,
                new net.minecraft.world.phys.AABB(from, end).inflate(1.0), this::burnable, 0.0f);
        if (hit != null) {
            LaserDamage.burn(level, from, hit.getEntity(), getData().power, shooter);
        } else {
            LaserDamage.draw(level, from, end);
        }
        markShot();
        return true;
    }

    /** Autonomous: one beam tick at {@code target} if the line from the muzzle is clear. */
    public boolean burn(Entity target, @Nullable Entity shooter) {
        if (!(vehicle.level() instanceof ServerLevel level) || !ready()) {
            return false;
        }
        Vec3 from = weaponUnit.aimContext().from;
        Vec3 to = target.getBoundingBox().getCenter();
        if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (Entity) null))
                .getType() != HitResult.Type.MISS) {
            return false;
        }
        drawPower();
        LaserDamage.burn(level, from, target, getData().power, shooter);
        return true;
    }

    private boolean burnable(Entity e) {
        return e.isAlive() && e != vehicle && !vehicle.hasPassenger(e) && e.isPickable();
    }
}
