package com.wf.combinedassault.api.custom;

import net.minecraft.resources.ResourceLocation;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;

import java.util.Map;
import java.util.Optional;

public interface IVehicleDataManager {

    Map<ResourceLocation, BaseVehicleData> getVehicleData();

    Optional<BaseVehicleData> getVehicleData(ResourceLocation id);

}
