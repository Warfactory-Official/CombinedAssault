package com.wf.combinedassault.vehicle.entity;

import com.wf.combinedassault.CombinedAssault;
import net.minecraft.resources.ResourceLocation;

public interface ICustomVehicle {

    String TAG_VEHICLE_ID = "CombinedAssaultId";
    String TAG_VEHICLE_DISPLAY_ID = "CombinedAssaultDisplayId";
    ResourceLocation EMPTY_ID = CombinedAssault.modLocation("empty");

    ResourceLocation getVehicleId();

    void setVehicleId(ResourceLocation vehicleId);

    ResourceLocation getDisplayId();

    void setDisplayId(ResourceLocation displayId);

}
