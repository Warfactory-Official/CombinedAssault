package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** UAV autopilot: absent => manual only. {@code tasks} = wflib {@code DroneTask.Kind} ids it may fly. */
public class AutonomyInfo {

    @SerializedName("tasks")
    public List<String> tasks = List.of();

    /** blocks/tick; null => wflib default. */
    @SerializedName("cruise_speed")
    public Double cruiseSpeed;

    /** Fixed wing: slowest commanded airspeed (orbit, climb-out, rotate at 0.9x), blocks/tick. */
    @SerializedName("min_speed")
    public double minSpeed = 0.8;

    /** Attack-run speed, blocks/tick; null => cruise. */
    @SerializedName("release_speed")
    public Double releaseSpeed;

    /** blocks above ground; null => wflib default. */
    @SerializedName("cruise_altitude")
    public Double cruiseAltitude;

}
