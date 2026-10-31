package com.wf.combinedassault.ballistics.hit;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Socket-role nodes, rest pose, vehicle frame (blocks, +X port, +Z forward). {@link MountDefaults} turns them into
 * vehicle def defaults.
 *
 * @param links  every glTF node, reader order; {@link Mount#node} and {@link Link#parent} index it
 * @param wheels drawn nodes with meta {@code road_wheel}
 */
public record ModelMounts(List<Link> links, List<Mount> mounts, List<Wheel> wheels, ModelRig rig) {

    /** @param centre rest origin */
    public record Wheel(String name, Vector3f centre) {
    }

    public record Link(String name, int parent, Matrix4f world) {

        public Vector3f origin() {
            return world.getTranslation(new Vector3f());
        }

        public Quaternionf rotation() {
            return world.getUnnormalizedRotation(new Quaternionf());
        }
    }

    /**
     * @param target    {@code combinedassaultInterface}: part id or entry id, "" if none
     * @param index     {@code combinedassault.index}, -1 if none
     * @param triangles world positions, 9 per triangle; empty for an empty
     * @param kit       kit-scoped part mount it came from, or null
     */
    public record Mount(int node, String name, NodeRole role, String target, int index, Vector3f origin,
                        Quaternionf rotation, Vector3f scale, float[] triangles, @Nullable String kit) {
    }

    /** Rig frame node of {@code part}: pitch => its pitch frame, else (or none) its part frame. -1: none. */
    public int partFrame(String part, boolean pitch) {
        int frame = pitch ? rig.pitch(part) : -1;
        if (frame < 0) {
            frame = rig.part(part);
        }
        return frame < 0 ? -1 : rig.frames().get(frame).node();
    }

    /** Name of the frame node {@code at} rides. */
    public String frameName(int at) {
        return rig.frames().get(rig.nodeFrame()[at]).name();
    }
}
