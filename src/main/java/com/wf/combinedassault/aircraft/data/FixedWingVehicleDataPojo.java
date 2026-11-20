package com.wf.combinedassault.aircraft.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.aircraft.pojo.Exhaust;
import com.wf.combinedassault.vehicle.data.BaseVehicleDataPojo;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class FixedWingVehicleDataPojo extends BaseVehicleDataPojo {

    @SerializedName("attributes")
    public FixedWingAttributes attributes;

    @SerializedName("landing_gear_part_id")
    public String landingGearPartId;

    @SerializedName("airbrake_part_id")
    public String airbrakePartId;

    @SerializedName("thrust_part_id")
    public String thrustPartId;

    public static class FixedWingAttributes {

        @SerializedName("thrust")
        public float thrust = 8000f;

        @SerializedName("thrust_k")
        public float thrustK = 1.5f;

        @SerializedName("ceiling")
        public float ceiling = 512;

        @SerializedName("ceiling_hard")
        public float ceilingHard;

        @SerializedName("x_rot_input_step")
        public float xRotInputStep = 0.2f;

        @SerializedName("y_rot_input_step")
        public float yRotInputStep = 0.5f;

        @SerializedName("z_rot_input_step")
        public float zRotInputStep = 0.2f;

        @SerializedName("air_drag_k_min")
        public float airDragKMin = 2f;

        @SerializedName("air_drag_k_max")
        public float airDragKMax = 8f;

        @SerializedName("lift_to_drag_k")
        public float liftToDragK = 6;

        /** Degrees, + = nose up: attitude the gear stance eases to on the ground (taildragger 3-point). */
        @SerializedName("ground_pitch")
        public float groundPitch = 0;

        @SerializedName("angle_of_attack_min")
        public float angleOfAttackMin = -10f;

        @SerializedName("angle_of_attack_max")
        public float angleOfAttackMax = 25f;

        @SerializedName("x_rot_input_drag_k")
        public float xRotInputDragK = 1f;

        @SerializedName("y_rot_input_drag_k")
        public float yRotInputDragK = 1f / 4;

        @SerializedName("z_rot_input_drag_k")
        public float zRotInputDragK = 1f / 8;

        @SerializedName("turn_rate_by_speed")
        public float turnRateBySpeed = 1f / 2.5f;

        @SerializedName("x_turn_rate")
        public float xTurnRate = 2;

        @SerializedName("y_turn_rate")
        public float yTurnRate = 3;

        @SerializedName("z_turn_rate")
        public float zTurnRate = 8;

        /**
         * Fin share of yaw stability (weathercock, side force); the rest is flown by drag rudders: rate-limited by
         * {@code y_turn_rate}, charged as yaw input drag. Flying wing ~0.2.
         */
        @SerializedName("directional_stability")
        public float directionalStability = 1;

        /** m/s; {@code thrust} holds to this airspeed, then falls as 1 / airspeed (constant power). 0 = jet. */
        @SerializedName("propeller_speed")
        public float propellerSpeed = 0;

        /** m/s; slipstream over the tail at full power: added to airspeed for pitch / yaw authority. */
        @SerializedName("propeller_wash")
        public float propellerWash = 0;

        /** Hover pitch/roll authority from reaction controls, x/z_turn_rate share at full power, nozzles vertical. */
        @SerializedName("vtol_turn_rate")
        public float vtolTurnRate = 0;

        @SerializedName("vortex_offsets")
        public List<Vec3> vortexOffsets = new ArrayList<>();

        @SerializedName("exhausts")
        public List<Exhaust> exhausts = new ArrayList<>();

        @SerializedName("aerobatic_smoke_offsets")
        public List<Vec3> aerobaticSmokeOffsets = new ArrayList<>();

    }

}
