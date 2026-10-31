package com.wf.combinedassault.naval.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.data.BaseVehicleDataPojo;

public class VesselVehicleDataPojo extends BaseVehicleDataPojo {

    @SerializedName("attributes")
    public VesselVehicleAttributes attributes;

    public static class VesselVehicleAttributes {

        @SerializedName("brake_force")
        public float brakeForce;

        @SerializedName("forward_force")
        public float forwardForce;

        @SerializedName("backward_force")
        public float backwardForce;

        @SerializedName("max_speed_forward")
        public float maxSpeedForward;

        @SerializedName("max_speed_backward")
        public float maxSpeedBackward;

        @SerializedName("turn_step")
        public float turnStep;

        @SerializedName("max_turn")
        public float maxTurn;

    }

}
