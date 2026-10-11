package com.wf.combinedassault.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.api.custom.IVehicleDataManager;
import com.wf.combinedassault.api.custom.IVehicleWeaponManager;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.Optional;

public class CombinedAssaultAPI {

    public static AbstractVehicle summon(Level level, Vec3 position, float yRot, ResourceLocation vehicleId, ResourceLocation displayId) {
        Optional<BaseVehicleData> vehicleDataOptional = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId);
        if (vehicleDataOptional.isPresent()) {
            AbstractVehicle vehicle = vehicleDataOptional.get().construct(level, position, 0, yRot);
            vehicle.setDisplayId(displayId);
            level.addFreshEntity(vehicle);
            return vehicle;
        }
        return null;
    }

    public static void changeDisplay(AbstractVehicle vehicle, ResourceLocation displayId) {
        if (vehicle != null) {
            vehicle.setDisplayId(displayId);
        }
    }

    public static IVehicleWeaponManager getVehicleWeaponManager() {
        return CommonAssetsManager.vehicleWeaponManager();
    }

    public static IVehicleDataManager getVehicleDataManager() {
        return CommonAssetsManager.vehicleDataManager();
    }

}
