package com.wf.combinedassault.vehicle.weapon.seeker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.wf.wflib.api.SightBlocker;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;

public class ElectroOptical {

    public static Entity checkTarget(WeaponUnit weaponUnit, Entity target) {
        Vec3 checkStart = weaponUnit.worldPivotPosition();
        Vec3 checkEnd = target.getBoundingBox().getCenter();
        Level level = target.level();
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        BlockHitResult result = level.clip(new ClipContext(checkStart, checkEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        if (result.getType() != HitResult.Type.MISS) {
            BlockPos pos = result.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() && state.canOcclude()) {
                return null;
            }
        }
        EntityHitResult entityHit = VectorUtil.hitEntity(vehicle, checkStart, checkEnd);
        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof SightBlocker) {
                return null;
            }
        }
        return target;
    }

    public static Entity findTarget(WeaponUnit weaponUnit, Vec3 lookAtPos) {
        Vec3 opticalSightPosition = weaponUnit.worldOpticalSightPosition(1f);
        Vec3 direction = lookAtPos.subtract(opticalSightPosition).normalize();
        EntityHitResult entityHit = VectorUtil.hitEntity(weaponUnit.getVehicle(), opticalSightPosition, opticalSightPosition.add(direction.scale(LocalVehiclePlayer.renderDistance())));
        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof SightBlocker) {
                return null;
            } else {
                return entity;
            }
        } else {
            return null;
        }
    }

}
