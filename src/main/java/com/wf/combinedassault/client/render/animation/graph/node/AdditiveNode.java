package com.wf.combinedassault.client.render.animation.graph.node;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.client.render.animation.util.PoseBlenders;

public class AdditiveNode implements PoseNode {

    private final PoseNode baseNode;
    private final PoseNode addNode;

    public AdditiveNode(PoseNode baseNode, PoseNode addNode) {
        this.baseNode = baseNode;
        this.addNode = addNode;
    }

    /** Null input: returns the other pose unchanged. */
    @Override
    public Pose evaluate(IAnimationInstance<?> context) {
        Pose basePose = baseNode.evaluate(context);
        Pose addPose = addNode.evaluate(context);

        if (basePose == null) return addPose;
        if (addPose == null) return basePose;

        return PoseBlenders.BLENDER.blend(basePose, addPose);
    }

}
