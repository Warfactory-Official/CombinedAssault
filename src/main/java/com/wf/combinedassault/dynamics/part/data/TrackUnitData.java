package com.wf.combinedassault.dynamics.part.data;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TrackUnitData extends SuspensionUnitData {

    private static final float COLLISION_THICKNESS = 1.0f / 16;
    private final float trackWidth;
    private List<List<Vec3>> tracks;
    private final List<TrackUnitPojo.RoadWheel> roadWheels;

    public TrackUnitData(TrackUnitPojo pojo) {
        super(pojo);
        this.trackWidth = pojo.trackWidth;
        this.tracks = copyTracks(pojo.tracks);
        this.roadWheels = List.copyOf(pojo.roadWheels);
        for (TrackUnitPojo.RoadWheel wheel : roadWheels) {
            if (wheel.track() < 0 || wheel.track() >= tracks.size()) {
                throw new IllegalArgumentException("road wheel " + wheel.node() + " names track " + wheel.track()
                        + " of " + tracks.size());
            }
        }
    }

    private static List<List<Vec3>> copyTracks(List<List<Vec3>> tracks) {
        if (tracks == null) {
            return List.of();
        }
        List<List<Vec3>> result = new ArrayList<>();
        for (List<Vec3> track : tracks) {
            if (track == null) {
                continue;
            }
            List<Vec3> points = track.stream().filter(point -> point != null).toList();
            if (!points.isEmpty()) {
                result.add(points);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void initFrames(@org.jetbrains.annotations.Nullable VehicleCubeGroup part,
                           @org.jetbrains.annotations.Nullable VehicleCubeGroup pitch) {
        super.initFrames(part, pitch);
        partCubeOBBs = new ArrayList<>();
        if (part == null) {
            return;
        }
        for (List<Vec3> points : tracks) {
            buildCollisionStructure(points, new Vec3(1, 0, 0));
        }
    }

    private void buildCollisionStructure(List<Vec3> points, Vec3 widthAxis) {
        var transform = structureGroup.globalTransform();
        Quaternionf inverseRotation = new Quaternionf(transform.rotation()).invert();
        Vec3 localWidthAxis = new Vec3(inverseRotation.transform(widthAxis.toVector3f()));
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (Vec3 point : points) {
            Vector3f localPoint = inverseRotation.transform(point.subtract(transform.offset()).toVector3f());
            min.min(localPoint);
            max.max(localPoint);
        }
        // One OBB per track; lateral extent padded by track width.
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        Vector3f extents = new Vector3f(max).sub(min).mul(0.5f).add(
                (float) Math.abs(localWidthAxis.x) * trackWidth / 2,
                (float) Math.abs(localWidthAxis.y) * trackWidth / 2 + COLLISION_THICKNESS / 2,
                (float) Math.abs(localWidthAxis.z) * trackWidth / 2);
        extents.max(new Vector3f(COLLISION_THICKNESS / 2));
        VehicleCubeOBB cube = new VehicleCubeOBB(new OBB(new Vector3f(), extents,
                new Quaternionf(structureGroup.rotation)));
        cube.group = structureGroup;
        cube.x = center.x - extents.x;
        cube.y = center.y - extents.y;
        cube.z = center.z - extents.z;
        cube.rebuild();
        partCubeOBBs.add(cube);
    }

    public float getTrackWidth() {
        return trackWidth;
    }

    public List<List<Vec3>> getTracks() {
        return tracks;
    }

    public List<TrackUnitPojo.RoadWheel> getRoadWheels() {
        return roadWheels;
    }

}
