package com.wf.combinedassault.render.animation.util;

import com.wf.combinedassault.render.model.BoneIndexProvider;
import com.maydaymemory.mae.basic.*;
import org.joml.Vector3f;

import java.util.TreeMap;

public class PoseHelper {

    public static final PoseHelper DUMMY = new PoseHelper(DummyPose.INSTANCE);
    private static final ZYXBoneTransformFactory TRANSFORM_FACTORY = new ZYXBoneTransformFactory();
    private final BoneIndexProvider boneIndexProvider;
    private final TreeMap<Integer, BoneTransformData> transforms = new TreeMap<>();
    private Pose pose;

    public PoseHelper(BoneIndexProvider boneIndexProvider) {
        this.boneIndexProvider = boneIndexProvider;
    }

    public PoseHelper(Pose pose) {
        this.pose = pose;
        this.boneIndexProvider = null;
    }

    public void hideBone(String boneName) {
        int boneIndex = boneIndexProvider.getIndex(boneName);
        if (boneIndex < 0) {
            return;
        }
        transforms.put(boneIndex, new BoneTransformData(
                0, 0, 0,
                0, 0, 0,
                0, 0, 0
        ));
    }

    /** rotX/rotY/rotZ: degrees. */
    public void setBone(String boneName,
                       double rotX, double rotY, double rotZ,
                       double transX, double transY, double transZ) {
        int boneIndex = boneIndexProvider.getIndex(boneName);
        if (boneIndex < 0) {
            return;
        }
        transforms.put(boneIndex, new BoneTransformData(
            (float) rotX, (float) rotY, (float) rotZ,
            (float) transX, (float) transY, (float) transZ,
            1.0f, 1.0f, 1.0f
        ));
    }

    /** rotX/rotY/rotZ: degrees. */
    public void setBoneWithScale(String boneName,
                                double rotX, double rotY, double rotZ,
                                double transX, double transY, double transZ,
                                double scaleX, double scaleY, double scaleZ) {
        int boneIndex = boneIndexProvider.getIndex(boneName);
        if (boneIndex < 0) {
            System.err.println("Bone not found: " + boneName);
            return;
        }
        transforms.put(boneIndex, new BoneTransformData(
            (float) rotX, (float) rotY, (float) rotZ,
            (float) transX, (float) transY, (float) transZ,
            (float) scaleX, (float) scaleY, (float) scaleZ
        ));
    }

    /** Degrees. */
    public void setRotation(String boneName, double rotX, double rotY, double rotZ) {
        setBone(boneName, rotX, rotY, rotZ, 0, 0, 0);
    }

    public void setTranslation(String boneName, double transX, double transY, double transZ) {
        setBone(boneName, 0, 0, 0, transX, transY, transZ);
    }

    public void clear() {
        transforms.clear();
    }

    public Pose build() {
        if (pose != null) {
            return pose;
        }

        ArrayPoseBuilder builder = new ArrayPoseBuilder();

        for (var entry : transforms.entrySet()) {
            int boneIndex = entry.getKey();
            BoneTransformData data = entry.getValue();

            float rotXRad = (float) Math.toRadians(data.rotX);
            float rotYRad = (float) Math.toRadians(data.rotY);
            float rotZRad = (float) Math.toRadians(data.rotZ);

            BoneTransform transform = TRANSFORM_FACTORY.createBoneTransform(
                boneIndex,
                new Vector3f(data.transX, data.transY, data.transZ),
                new Vector3f(rotXRad, rotYRad, rotZRad),
                new Vector3f(data.scaleX, data.scaleY, data.scaleZ)
            );
            
            builder.addBoneTransform(transform);
        }
        
        return builder.toPose();
    }

    private record BoneTransformData(float rotX, float rotY, float rotZ,
                                     float transX, float transY, float transZ,
                                     float scaleX, float scaleY, float scaleZ) {}

}
