package com.wf.combinedassault.all;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.serialize.GsonUtil;
import com.wf.combinedassault.custom.vehicle.*;

public class AllVehicleDataTypes {

    public static final DeferredRegister<VehicleDataType<?>> VEHICLE_TYPES = DeferredRegister.create(ModRegistries.VEHICLE_DATA_TYPE_KEY, CombinedAssault.MOD_ID);

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<BaseVehicleData>> GENERIC_VEHICLE = register(
            "generic",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, BaseVehicleDataPojo.class);
                BaseVehicleData baseVehicleData = new BaseVehicleData();
                baseVehicleData.build(pojo);
                return baseVehicleData;
            }
    );

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<WheeledVehicleData>> WHEELED_VEHICLE = register(
            "wheeled_vehicle",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, WheeledVehicleDataPojo.class);
                WheeledVehicleData wheeledVehicleData = new WheeledVehicleData();
                wheeledVehicleData.build(pojo);
                return wheeledVehicleData;
            }
    );

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<TrackedVehicleData>> TRACKED_VEHICLE = register(
            "tracked_vehicle",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, TrackedVehicleDataPojo.class);
                TrackedVehicleData trackedVehicleData = new TrackedVehicleData();
                trackedVehicleData.build(pojo);
                return trackedVehicleData;
            }
    );

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<VesselVehicleData>> VESSEL_VEHICLE = register(
            "vessel_vehicle",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, VesselVehicleDataPojo.class);
                VesselVehicleData vesselVehicleData = new VesselVehicleData();
                vesselVehicleData.build(pojo);
                return vesselVehicleData;
            }
    );

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<RotaryWingVehicleData>> ROTARY_WING_VEHICLE = register(
            "rotary_wing_vehicle",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, RotaryWingVehicleDataPojo.class);
                RotaryWingVehicleData rotaryWingVehicleData = new RotaryWingVehicleData();
                rotaryWingVehicleData.build(pojo);
                return rotaryWingVehicleData;
            }
    );

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<FixedWingVehicleData>> FIXED_WING_VEHICLE = register(
            "fixed_wing_vehicle",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, FixedWingVehicleDataPojo.class);
                FixedWingVehicleData fixedWingVehicleData = new FixedWingVehicleData();
                fixedWingVehicleData.build(pojo);
                return fixedWingVehicleData;
            }
    );

    public static final DeferredHolder<VehicleDataType<?>, VehicleDataType<ShipVehicleData>> SHIP = register(
            "ship",
            json -> {
                var pojo = GsonUtil.GSON.fromJson(json, ShipVehicleDataPojo.class);
                ShipVehicleData shipVehicleData = new ShipVehicleData();
                shipVehicleData.build(pojo);
                return shipVehicleData;
            }
    );

    private static <D extends BaseVehicleData> DeferredHolder<VehicleDataType<?>, VehicleDataType<D>> register(
            String name,
            VehicleDataType.DataSerializer<D> dataSerializer
    ) {
        return VEHICLE_TYPES.register(name,
                () -> VehicleDataType.Builder.<D>of(CombinedAssault.modLocation(name))
                        .setDataSerializer(dataSerializer)
                        .build()
        );
    }

    public static void register(IEventBus eventBus) {
        VEHICLE_TYPES.register(eventBus);
    }

}
