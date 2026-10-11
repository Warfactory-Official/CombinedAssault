package com.wf.combinedassault.util;

import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.entity.misc.VehiclePart;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

public final class VehiclePartSpawner {

    private VehiclePartSpawner() {}

    public static void spawnDestroyedParts(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!partUnit.isDetachable()) {
                continue;
            }
            VehiclePart vehiclePart = partUnit.detach();
            if (vehiclePart == null) {
                continue;
            }
            vehiclePart.setDestroyed();
            vehiclePart.setDeltaMovement(flingVelocity(vehicle, partUnit));
            vehicle.level().addFreshEntity(vehiclePart);
        }
    }

    private static Vec3 flingVelocity(AbstractVehicle vehicle, PartUnit<?> partUnit) {
        Vec3 center = vehicle.position().add(vehicle.centerOffset);
        Vec3 direction = partUnit.worldPivotPosition().subtract(center);
        var random = vehicle.level().random;
        if (direction.lengthSqr() < 1e-6) {
            direction = new Vec3(random.nextDouble() - 0.5, 0.2, random.nextDouble() - 0.5);
        }
        Vec3 randomOffset = new Vec3(
                (random.nextDouble() - 0.5) * 0.3,
                (random.nextDouble() - 0.5) * 0.05,
                (random.nextDouble() - 0.5) * 0.3
        );
        direction = direction.normalize().add(randomOffset).normalize();
        double speed = vehicle.physicsEngine.destroyExplosionVelocity + random.nextDouble() * 0.2;
        return direction.scale(speed);
    }

}
