package com.wf.combinedassault.render.model;

import com.wf.combinedassault.ballistics.hit.ClipRig;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Model-space frame of a {@link ModelData} node as drawn: nearest posed ancestor bone x offset below it. Clips =>
 * offset posed by {@link ClipRig} (part mount clips: not bones of the vehicle instance). Render thread.
 */
public final class GraftedNodes {
    private static final Map<AbstractVehicle, Posed> POSED = new WeakHashMap<>();
    private static final Matrix4f SCRATCH = new Matrix4f();

    private GraftedNodes() {
    }

    private static final class Posed {
        long tick = Long.MIN_VALUE;
        float partialTick = Float.NaN;
        Matrix4f[] world;
        boolean[] moved;
    }

    public static Matrix4f node(AbstractVehicle vehicle, ModelData model, int node, boolean clips, float partialTick,
                                Matrix4f out) {
        List<ModelMounts.Link> links = model.mounts().links();
        Posed posed = clips ? posed(vehicle, partialTick) : null;
        out.set(world(links, posed, node));
        VehicleModelInstance instance = vehicle.getVehicleModelInstance();
        if (instance == null || !instance.posed()) {
            return out;
        }
        for (int at = node; at >= 0; at = links.get(at).parent()) {
            int bone = instance.boneIndex(links.get(at).name());
            if (bone >= 0) {
                SCRATCH.set(world(links, posed, at)).invertAffine().mul(out);
                return out.set(instance.globalTransform(bone)).mul(SCRATCH);
            }
        }
        return out;
    }

    private static Matrix4fc world(List<ModelMounts.Link> links, @Nullable Posed posed, int node) {
        return posed != null && posed.moved[node] ? posed.world[node] : links.get(node).world();
    }

    /** Once per vehicle per frame. */
    @Nullable
    private static Posed posed(AbstractVehicle vehicle, float partialTick) {
        PartClipTracks tracks = vehicle.clipTracks();
        if (tracks == null) {
            return null;
        }
        ClipRig rig = tracks.rig();
        Posed posed = POSED.computeIfAbsent(vehicle, v -> new Posed());
        long tick = vehicle.level().getGameTime();
        if (posed.world == null || posed.world.length != rig.nodeCount()) {
            posed.world = new Matrix4f[rig.nodeCount()];
            posed.moved = new boolean[rig.nodeCount()];
            posed.tick = Long.MIN_VALUE;
        }
        if (posed.tick != tick || posed.partialTick != partialTick) {
            rig.pose(tracks.clipTimes(partialTick), posed.world, posed.moved);
            posed.tick = tick;
            posed.partialTick = partialTick;
        }
        return posed;
    }
}
