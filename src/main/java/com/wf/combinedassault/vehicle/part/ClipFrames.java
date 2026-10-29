package com.wf.combinedassault.vehicle.part;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.hit.ClipRig;
import com.wf.combinedassault.vehicle.hit.ModelRig;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Frames flagged {@code follows_clips} (frame {@code extras.combinedassault}) whose part, if any, is not a
 * {@link RotatableUnit}: this vehicle's group of each takes the pose of its node under the clips at the tracks'
 * progress, so colliders and hit nodes riding it move with the picture. Ticked with {@link PartClipTracks}.
 */
public final class ClipFrames {
    private final PartClipTracks clips;
    private final ClipRig rig;
    private final int[] node;
    private final int[] parentNode;
    private final VehicleCubeGroup[] groups;
    private final Vector3f[] restPivot;
    private final Matrix4f[] world;
    private final boolean[] moved;
    private int posedVersion = -1;

    private ClipFrames(PartClipTracks clips, int[] node, int[] parentNode, VehicleCubeGroup[] groups, Vector3f[] restPivot) {
        this.clips = clips;
        this.rig = clips.rig();
        this.node = node;
        this.parentNode = parentNode;
        this.groups = groups;
        this.restPivot = restPivot;
        this.world = new Matrix4f[rig.nodeCount()];
        this.moved = new boolean[rig.nodeCount()];
    }

    /**
     * @param frames      model frames; {@code typeGroups} their type-level groups, {@code copies} this vehicle's
     * @return null: no frame follows clips
     */
    @Nullable
    public static ClipFrames bind(@Nullable PartClipTracks clips, List<ModelRig.Frame> frames,
                                  List<VehicleCubeGroup> typeGroups, Map<VehicleCubeGroup, VehicleCubeGroup> copies,
                                  Map<String, PartUnit<?>> parts) {
        if (clips == null) {
            return null;
        }
        List<Integer> chosen = new ArrayList<>();
        for (int f = 1; f < frames.size(); f++) {
            ModelRig.Frame frame = frames.get(f);
            boolean follows = frame.meta().has("follows_clips") && frame.meta().get("follows_clips").getAsBoolean();
            if (follows && !(parts.get(frame.part()) instanceof RotatableUnit<?>)) {
                chosen.add(f);
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        int n = chosen.size();
        int[] node = new int[n];
        int[] parentNode = new int[n];
        VehicleCubeGroup[] groups = new VehicleCubeGroup[n];
        Vector3f[] restPivot = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            ModelRig.Frame frame = frames.get(chosen.get(i));
            node[i] = frame.node();
            parentNode[i] = frames.get(Math.max(frame.parent(), ModelRig.BODY)).node();
            groups[i] = copies.get(typeGroups.get(chosen.get(i)));
            restPivot[i] = new Vector3f((float) groups[i].pivot.x, (float) groups[i].pivot.y, (float) groups[i].pivot.z);
        }
        ClipFrames bound = new ClipFrames(clips, node, parentNode, groups, restPivot);
        bound.refresh();
        return bound;
    }

    /** Frame node whose clip pose drives this vehicle's {@code group}, or -1. */
    public int nodeOf(VehicleCubeGroup group) {
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == group) {
                return node[i];
            }
        }
        return -1;
    }

    /** When a ticked clip pose changed. */
    public void refresh() {
        if (clips.version() == posedVersion) {
            return;
        }
        posedVersion = clips.version();
        rig.pose(clips.clipTimes(1), world, moved);
        Quaternionf parentRotation = new Quaternionf();
        Quaternionf rotation = new Quaternionf();
        Vector3f origin = new Vector3f();
        Vector3f parentOrigin = new Vector3f();
        for (int i = 0; i < groups.length; i++) {
            Matrix4f own = moved[node[i]] ? world[node[i]] : rig.restWorld(node[i]);
            Matrix4f up = moved[parentNode[i]] ? world[parentNode[i]] : rig.restWorld(parentNode[i]);
            own.getTranslation(origin);
            own.getUnnormalizedRotation(rotation).normalize();
            up.getTranslation(parentOrigin);
            up.getUnnormalizedRotation(parentRotation).normalize().conjugate();
            origin.sub(parentOrigin);
            parentRotation.transform(origin);
            parentRotation.mul(rotation, rotation);
            VehicleCubeGroup group = groups[i];
            group.rotationO = group.rotation;
            group.rotation = new Quaternionf(rotation);
            group.offsetO = group.offset;
            group.offset = new net.minecraft.world.phys.Vec3(origin.x - restPivot[i].x, origin.y - restPivot[i].y,
                    origin.z - restPivot[i].z);
        }
    }
}
