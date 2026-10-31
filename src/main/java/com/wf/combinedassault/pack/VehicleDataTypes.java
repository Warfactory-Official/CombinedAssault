package com.wf.combinedassault.pack;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.data.BaseVehicleDataPojo;
import com.wf.combinedassault.vehicle.data.TrackedVehicleData;
import com.wf.combinedassault.vehicle.data.TrackedVehicleDataPojo;
import com.wf.combinedassault.vehicle.data.VehicleDataType;
import com.wf.combinedassault.vehicle.data.WheeledVehicleData;
import com.wf.combinedassault.vehicle.data.WheeledVehicleDataPojo;

public class VehicleDataTypes {

    public static void registerTypes() {
        register(
                "generic",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, BaseVehicleDataPojo.class);
                    BaseVehicleData baseVehicleData = new BaseVehicleData();
                    baseVehicleData.build(pojo);
                    return baseVehicleData;
                }
        );

        register(
                "wheeled_vehicle",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, WheeledVehicleDataPojo.class);
                    WheeledVehicleData wheeledVehicleData = new WheeledVehicleData();
                    wheeledVehicleData.build(pojo);
                    return wheeledVehicleData;
                }
        );

        register(
                "tracked_vehicle",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, TrackedVehicleDataPojo.class);
                    TrackedVehicleData trackedVehicleData = new TrackedVehicleData();
                    trackedVehicleData.build(pojo);
                    return trackedVehicleData;
                }
        );
    }

    public static <D extends BaseVehicleData> void register(String name, VehicleDataType.DataSerializer<D> dataSerializer) {
        VehicleDataType<D> type = VehicleDataType.Builder.<D>of(CombinedAssault.modLocation(name))
                .setDataSerializer(dataSerializer)
                .build();
        PackTypes.VEHICLE_DATA.register(type.id(), type);
    }

}
