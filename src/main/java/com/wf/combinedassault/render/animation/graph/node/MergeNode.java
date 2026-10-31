package com.wf.combinedassault.render.animation.graph.node;

import com.maydaymemory.mae.basic.DummyPose;
import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.pack.IAnimationInstance;
import com.wf.combinedassault.render.animation.util.PoseBlenders;

import java.util.ArrayList;
import java.util.List;

public class MergeNode implements PoseNode {

    public List<PoseNode> inputNodes;

    public MergeNode(List<PoseNode> inputNodes) {
        this.inputNodes = inputNodes;
    }

    /** Never null (empty => DummyPose); null/dummy inputs skipped before merge. */
    @Override
    public Pose evaluate(IAnimationInstance<?> context) {
        if (this.inputNodes == null || this.inputNodes.isEmpty()) {
            return DummyPose.INSTANCE;
        }
        List<Pose> poses = new ArrayList<>();
        for (PoseNode node : inputNodes) {
            Pose pose = node.evaluate(context);
            if (pose != null && pose != DummyPose.INSTANCE) {
                poses.add(pose);
            }
        }
        return PoseBlenders.MERGE_BLENDER.blend(poses);
    }

}
