package com.wf.combinedassault.vehicle.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.drive.Transmission;

public class TrackedVehicleDataPojo extends BaseVehicleDataPojo {

    @SerializedName("attributes")
    public TrackedVehicleAttributes attributes;

    public static class TrackedVehicleAttributes {

        @SerializedName("brake_acceleration")
        public float brakeAcceleration;

        @SerializedName("forward_acceleration")
        public float forwardAcceleration;

        @SerializedName("backward_acceleration")
        public float backwardAcceleration;

        /** kW at the wheels; 0 => none (force/acceleration to top speed). */
        @SerializedName("engine_power")
        public float enginePower;

        @SerializedName("max_speed_forward")
        public float maxSpeedForward;

        @SerializedName("max_speed_backward")
        public float maxSpeedBackward;

        @SerializedName("turn_acceleration")
        public float turnAcceleration;

        @SerializedName("max_turn")
        public float maxTurn;

        @SerializedName("track_size")
        public float trackSize = 0.3f;

        /** Null => direct drive. */
        @SerializedName("transmission")
        public Transmission transmission;

    }

}
