package com.wf.combinedassault.dynamics;

import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

public final class VehiclePartSpawner {

    /** Blocks/tick. */
    private static final double POP_SPEED = 0.15;
    /** Rad/tick. */
    private static final double POP_TOPPLE = 0.12;

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

    /** Destroyed wheel off a live vehicle: rolls clear, outward and up; no cube => just gone. */
    public static void popOff(PartUnit<?> partUnit) {
        AbstractVehicle vehicle = partUnit.getVehicle();
        VehiclePart vehiclePart = partUnit.detach();
        if (vehiclePart == null) {
            partUnit.setDetached(true);
            return;
        }
        Vec3 out = partUnit.worldPivotPosition().subtract(vehicle.position().add(vehicle.centerOffset)).multiply(1, 0, 1);
        out = out.lengthSqr() < 1e-6 ? Vec3.ZERO : out.normalize();
        vehiclePart.setDestroyed();
        vehiclePart.setDeltaMovement(vehicle.getDeltaMovement().add(out.scale(POP_SPEED)).add(0, POP_SPEED, 0));
        // Box of a wheel stands on its tread face: top tipped outward (up x out) or it never falls.
        vehiclePart.physicsEngine.angularVelocity.set(new Vec3(0, 1, 0).cross(out).scale(POP_TOPPLE).toVector3f());
        vehicle.level().addFreshEntity(vehiclePart);
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
