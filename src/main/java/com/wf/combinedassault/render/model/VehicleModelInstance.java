package com.wf.combinedassault.render.model;

import com.maydaymemory.mae.basic.Pose;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.Set;

/**
 * One vehicle's copy of a {@link VehicleModel}: the pose it is standing in, and how it is drawn.
 *
 * <p>Created per entity, never shared, and only ever touched on the client. Bone indices are the ones
 * {@link VehicleModel#boneIndexProvider()} hands out.
 */
public interface VehicleModelInstance {

    VehicleModel model();

    /** The pose the artist modelled, which every animation is blended onto. */
    Pose bindPose();

    /** Once a frame, from the render thread. */
    void applyPose(Pose pose);

    /** The graph's own pose (before blending onto the bind pose), ahead of {@link #applyPose}. */
    default void claim(Pose animation) {
    }

    /**
     * Advance part-driven motion (glTF clips, suspension travel) of this instance's own vehicle. Before
     * {@link #applyPose}.
     */
    default void driveParts(AbstractVehicle vehicle, float partialTick) {
    }

    /** Back to the bind pose, every bone visible and unlit. */
    void resetPose();

    /** -1 if the model has no such bone. */
    int boneIndex(String boneName);

    boolean hasBone(int boneIndex);

    /** The bone's name, or "" if the model has no such bone. */
    String boneName(int boneIndex);

    void setBoneVisible(String boneName, boolean visible);

    /** Draw only {@code bone}'s subtree minus {@code excluded} subtrees; null bone => whole model. */
    void isolate(@Nullable String bone, Set<String> excluded);

    void setBoneIlluminated(String boneName, boolean illuminated);

    /** Where the bone is, relative to the model root, in the pose applied this frame. */
    Matrix4f globalTransform(int boneIndex);

    /** False until a pose is published: {@link #globalTransform} identity. */
    boolean posed();

    /** {@link #globalTransform} composed onto the stack, normals included. */
    void mulGlobalTransform(PoseStack poseStack, int boneIndex);

    /** Publish this frame's pose to the instanced visual; geometry is retained, nothing is emitted. */
    void publish();
}
