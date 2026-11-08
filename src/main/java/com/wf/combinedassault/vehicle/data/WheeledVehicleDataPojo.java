package com.wf.combinedassault.vehicle.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.drive.Transmission;

public class WheeledVehicleDataPojo extends BaseVehicleDataPojo {

    @SerializedName("attributes")
    public WheeledVehicleDataPojo.WheeledVehicleAttributes attributes;

    public static class WheeledVehicleAttributes {

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

        /** Blocks/tick where steering is halved; NaN => {@code max_speed_forward}. */
        @SerializedName("steer_fade_speed")
        public float steerFadeSpeed = Float.NaN;

        @SerializedName("turn_step")
        public float turnStep;

        @SerializedName("max_turn")
        public float maxTurn;

        @SerializedName("track_size")
        public float trackSize = 0.1f;

        /** Null => direct drive. */
        @SerializedName("transmission")
        public Transmission transmission;

    }

}
