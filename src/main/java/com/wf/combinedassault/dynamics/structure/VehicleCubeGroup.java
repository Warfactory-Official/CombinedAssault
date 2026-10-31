package com.wf.combinedassault.dynamics.structure;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class VehicleCubeGroup {

    public VehicleCubeGroup parent;
    public List<VehicleCubeGroup> children = new ArrayList<>();
    public Quaternionf baseRotation;
    public Quaternionf rotation;
    public Quaternionf rotationO;
    /** Relative to the parent group's pivot. */
    public Vec3 pivot;
    /** Relative to the vehicle's own pivot, not the parent's. */
    public Vec3 pivotOffset;
    /** Relative to the parent group's pivot, summed with {@link #pivot}. */
    public Vec3 offset = Vec3.ZERO;
    public Vec3 offsetO = Vec3.ZERO;
    public List<VehicleCubeOBB> cubeOBBs = new ArrayList<>();
    /**
     * The model bone this group was built from, or empty for a group with no bone of its own.
     *
     * <p>Carried so that a ray traced against the hull's collision cubes can name what it hit. Everything
     * that attaches to a vehicle — decorations, camouflage — attaches by bone name, and without this the
     * only way to get one was the baked model, which exists on the client alone.
     */
    public final String name;

    public record GlobalTransform(Vec3 offset, Quaternionf rotation) {}

    public VehicleCubeGroup(VehicleCubeGroup parent, Quaternionf rotation, Vec3 pivot) {
        this(parent, rotation, pivot, "");
    }

    public VehicleCubeGroup(VehicleCubeGroup parent, Quaternionf rotation, Vec3 pivot, String name) {
        this.name = name == null ? "" : name;
        this.parent = parent;
        if (this.parent != null) {
            this.parent.addChild(this);
        }
        this.baseRotation = rotation;
        this.rotation = rotation;
        this.rotationO = new Quaternionf(rotation);
        this.pivot = pivot;
        this.pivotOffset = globalTransform().offset;
    }

    public void addChild(VehicleCubeGroup child) {
        this.children.add(child);
    }

    public void addCubeOBB(VehicleCubeOBB cubeOBB) {
        cubeOBBs.add(cubeOBB);
    }

    /** The nearest ancestor that came from a named bone, or null if none did. */
    public VehicleCubeGroup namedAncestor() {
        for (VehicleCubeGroup group = this; group != null; group = group.parent) {
            if (!group.name.isEmpty()) {
                return group;
            }
        }
        return null;
    }

    public VehicleCubeGroup.GlobalTransform globalTransform() {
        return globalTransform(Vec3.ZERO, false);
    }

    /**
     * This group's transform as it should be drawn this frame.
     *
     * <p>Group rotations move once a tick, so anything rendered in a group's frame has to interpolate the
     * same way {@link com.wf.combinedassault.vehicle.part.PartUnit} does, or a turning turret drags whatever is
     * attached to it a tick behind the model.
     */
    public VehicleCubeGroup.GlobalTransform globalTransform(float partialTick) {
        return globalTransform(Vec3.ZERO, false, group -> viewRotation(group, partialTick),
                group -> group.offsetO.lerp(group.offset, partialTick));
    }

    /** A group's rotation partway between the last tick's and this one's. */
    public static Quaternionf viewRotation(VehicleCubeGroup group, float partialTick) {
        if (partialTick != 1.0F && group.rotationO != null) {
            return new Quaternionf(group.rotationO).slerp(group.rotation, partialTick);
        }
        return new Quaternionf(group.rotation);
    }

    public VehicleCubeGroup.GlobalTransform globalTransform(Vec3 offset, boolean withSelfRotation) {
        return globalTransform(offset, withSelfRotation, group -> group.rotation, group -> group.offset);
    }

    public VehicleCubeGroup.GlobalTransform globalTransform(Vec3 offset, boolean withSelfRotation,
                                                            Function<VehicleCubeGroup, Quaternionf> rotationProvider,
                                                            Function<VehicleCubeGroup, Vec3> offsetProvider) {
        Quaternionf selfRotation = rotationProvider.apply(this);
        Quaternionf globalRotation = new Quaternionf(selfRotation);
        Vector3f globalPivot = pivot.add(offsetProvider.apply(this)).toVector3f()
                .add(withSelfRotation ? selfRotation.transform(offset.toVector3f()) : offset.toVector3f());
        VehicleCubeGroup parentGroup = parent;
        while (parentGroup != null) {
            Quaternionf parentRotation = rotationProvider.apply(parentGroup);
            parentRotation.transform(globalPivot);
            Vec3 parentPivot = parentGroup.pivot.add(offsetProvider.apply(parentGroup));
            globalPivot.add((float) parentPivot.x, (float) parentPivot.y, (float) parentPivot.z);
            globalRotation.premul(parentRotation);
            parentGroup = parentGroup.parent;
        }
        return new VehicleCubeGroup.GlobalTransform(new Vec3(globalPivot), globalRotation);
    }

}
