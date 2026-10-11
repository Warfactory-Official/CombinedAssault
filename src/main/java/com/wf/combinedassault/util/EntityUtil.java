package com.wf.combinedassault.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ServerVehicleHurtEntity;

import java.util.OptionalDouble;

public class EntityUtil {


    public static void hurt(DamageSource source, Entity entity, float damage) {
        boolean kill = false;
        boolean destroyedBeforeHurt = false;
        if (entity instanceof AbstractVehicle vehicle) {
            destroyedBeforeHurt = vehicle.isDestroyed();
        }
        entity.hurt(source, damage);
        if (entity instanceof AbstractVehicle vehicle) {
            kill = !destroyedBeforeHurt && vehicle.isDestroyed();
        } else if (entity instanceof LivingEntity livingEntity) {
            kill = livingEntity.isDeadOrDying();
        }
        if (source.getEntity() instanceof ServerPlayer serverPlayer
                && serverPlayer.getVehicle() instanceof AbstractVehicle vehicle) {
            PacketDistributor.sendToPlayer(serverPlayer, new ServerVehicleHurtEntity(vehicle.getId(), entity.getId(), entity instanceof AbstractVehicle, kill));
        }
    }

    public static OptionalDouble blockSurfaceY(Level level, Vec3 pos, double maxDistance) {
        int maxBlockY = Mth.floor(pos.y + maxDistance);
        int minBlockY = Mth.floor(pos.y - maxDistance);
        double surfaceY = Double.NEGATIVE_INFINITY;
        for (int blockY = maxBlockY; blockY >= minBlockY; blockY--) {
            BlockPos blockPos = BlockPos.containing(pos.x, blockY, pos.z);
            BlockState blockState = level.getBlockState(blockPos);
            VoxelShape shape = blockState.getCollisionShape(level, blockPos);
            double localX = pos.x - blockPos.getX();
            double localZ = pos.z - blockPos.getZ();
            for (AABB box : shape.toAabbs()) {
                if (localX < box.minX || localX > box.maxX || localZ < box.minZ || localZ > box.maxZ) {
                    continue;
                }
                double candidateY = blockPos.getY() + box.maxY;
                if (Math.abs(pos.y - candidateY) <= maxDistance) {
                    surfaceY = Math.max(surfaceY, candidateY);
                }
            }
        }
        return surfaceY == Double.NEGATIVE_INFINITY ? OptionalDouble.empty() : OptionalDouble.of(surfaceY);
    }

    public static boolean isOnBlockSurface(Entity entity, Vec3 pos) {
        BlockPos blockBelow = BlockPos.containing(pos.x, pos.y - 0.05, pos.z);
        BlockState stateBelow = entity.level().getBlockState(blockBelow);
        if (stateBelow.isAir()) return false;
        VoxelShape shape = stateBelow.getCollisionShape(entity.level(), blockBelow);
        if (shape.isEmpty()) return false;
        double surfaceY = shape.max(Direction.Axis.Y) + blockBelow.getY();
        return pos.y - surfaceY <= 0.05;
    }

    public static double getGroundY(Level level, Vec3 pos) {
        BlockPos blockPos = BlockPos.containing(pos);
        while (blockPos.getY() > level.getMinBuildHeight() && level.getBlockState(blockPos).isAir()) {
            blockPos = blockPos.below();
        }
        return blockPos.getY() + 1.0;
    }

}
