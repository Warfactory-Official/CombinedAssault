package com.wf.combinedassault.pack.client;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.PackTypes;
import com.wf.combinedassault.render.animation.context.AnimationContextFactory;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplayPojo;
import com.wf.combinedassault.pack.client.vehicle.SimpleVehicleDisplay;
import com.wf.combinedassault.pack.client.vehicle.TrackedVehicleDisplay;
import com.wf.combinedassault.pack.client.vehicle.TrackedVehicleDisplayPojo;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplayPojo;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplayType;
import com.wf.combinedassault.pack.client.vehicle.WheeledVehicleDisplay;

public class DisplayTypes {

    public static void registerTypes() {
        register(
                "generic",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, VehicleDisplayPojo.class);
                    return new SimpleVehicleDisplay(pojo);
                },
                AnimationContextFactory.vehicle()
        );

        register(
                "weapon",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, BaseDisplayPojo.class);
                    return new BaseDisplay(pojo);
                },
                null
        );

        register(
                "wheeled_vehicle",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, VehicleDisplayPojo.class);
                    return new WheeledVehicleDisplay(pojo);
                },
                AnimationContextFactory.wheeledVehicle()
        );

        register(
                "tracked_vehicle",
                json -> {
                    var pojo = GsonUtil.GSON.fromJson(json, TrackedVehicleDisplayPojo.class);
                    return new TrackedVehicleDisplay(pojo);
                },
                AnimationContextFactory.trackedVehicle()
        );
    }

    public static <D extends BaseDisplay> void register(
            String name,
            VehicleDisplayType.DataSerializer<D> dataSerializer,
            AnimationContextFactory<?, ?> contextFactory
    ) {
        VehicleDisplayType<D> type = VehicleDisplayType.Builder.<D>of(CombinedAssault.modLocation(name))
                .setDataSerializer(dataSerializer)
                .setContextFactory(contextFactory)
                .build();
        PackTypes.VEHICLE_DISPLAYS.register(type.id(), type);
    }

}
