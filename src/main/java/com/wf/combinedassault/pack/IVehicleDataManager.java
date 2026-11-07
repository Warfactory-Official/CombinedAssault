package com.wf.combinedassault.pack;

import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;

public interface IVehicleDataManager {

    Map<ResourceLocation, BaseVehicleData> getVehicleData();

    Optional<BaseVehicleData> getVehicleData(ResourceLocation id);

}
