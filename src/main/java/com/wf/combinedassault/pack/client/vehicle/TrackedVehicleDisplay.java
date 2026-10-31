package com.wf.combinedassault.pack.client.vehicle;

import com.wf.combinedassault.render.animation.context.TrackedVehicleContext;
import com.wf.combinedassault.vehicle.entity.TrackedVehicle;

import javax.annotation.Nullable;

public class TrackedVehicleDisplay extends VehicleDisplay<TrackedVehicle, TrackedVehicleContext> {

    @Nullable
    private final TrackConfig trackConfig;

    public TrackedVehicleDisplay(TrackedVehicleDisplayPojo pojo) {
        super(pojo);
        if (pojo.trackConfig != null && (pojo.trackConfig.model == null || pojo.trackConfig.moduleLength <= 0)) {
            throw new IllegalArgumentException("track_config needs a model and module_length > 0");
        }
        this.trackConfig = pojo.trackConfig;
    }

    @Nullable
    public TrackConfig getTrackConfig() {
        return trackConfig;
    }
}
