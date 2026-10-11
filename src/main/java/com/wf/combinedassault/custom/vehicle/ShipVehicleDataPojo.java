package com.wf.combinedassault.custom.vehicle;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** The {@code attributes} block of a {@code combinedassault:ship}. Units in {@code REDACTED}. */
public class ShipVehicleDataPojo extends BaseVehicleDataPojo {

    @SerializedName("attributes")
    public ShipAttributes attributes = new ShipAttributes();

    public static class ShipAttributes {

        @SerializedName("waterline")
        public double waterline = 0.0;

        /** Blocks from the waterline to the keel. Below zero means "to the physics hull's underside". */
        @SerializedName("draft")
        public double draft = -1.0;

        @SerializedName("max_speed_forward")
        public double maxSpeedForward = 0.5;

        @SerializedName("max_speed_backward")
        public double maxSpeedBackward = 0.2;

        @SerializedName("acceleration_time")
        public double accelerationTime = 10.0;

        @SerializedName("throttle_rate")
        public double throttleRate = 0.5;

        @SerializedName("rudder_rate")
        public double rudderRate = 1.0;

        @SerializedName("turn_rate")
        public double turnRate = 10.0;

        @SerializedName("turn_lag")
        public double turnLag = 1.0;

        @SerializedName("low_speed_turn")
        public double lowSpeedTurn = 0.0;

        @SerializedName("drift")
        public double drift = 0.15;

        @SerializedName("heel")
        public double heel = 1.0;

        @SerializedName("trim")
        public double trim = 2.0;

        @SerializedName("heave_period")
        public double heavePeriod = 3.0;

        @SerializedName("righting_rate")
        public double rightingRate = 4.0;

        @SerializedName("min_keel_clearance")
        public double minKeelClearance = 1.0;

        @SerializedName("shallow_drag")
        public double shallowDrag = 2.0;

        @SerializedName("submarine")
        public Submarine submarine;

        /** {@code [x, y, z, radius]} per screw. */
        @SerializedName("propellers")
        public List<double[]> propellers = List.of();

    }

    public static class Submarine {

        @SerializedName("max_depth")
        public double maxDepth = 40.0;

        @SerializedName("crush_depth")
        public double crushDepth = 60.0;

        @SerializedName("dive_rate")
        public double diveRate = 0.15;

        @SerializedName("max_speed_submerged")
        public double maxSpeedSubmerged = 0.4;

        @SerializedName("dive_angle")
        public double diveAngle = 12.0;

    }

}
