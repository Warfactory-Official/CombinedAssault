package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class TrackUnitPojo extends SuspensionUnitPojo {

    @SerializedName("track_width")
    public float trackWidth = 0.5f;

    @SerializedName("tracks")
    public List<List<Vec3>> tracks = new ArrayList<>();

    /** One probe each, at its centre x/z; drawn travel per wheel, lower run follows. */
    @SerializedName("road_wheels")
    public List<RoadWheel> roadWheels = List.of();

    /** @param track index into {@link #tracks} */
    public record RoadWheel(String node, int track, Vec3 center) {
    }

    public TrackUnitPojo() {
        this.isSeat = false;
    }

}
