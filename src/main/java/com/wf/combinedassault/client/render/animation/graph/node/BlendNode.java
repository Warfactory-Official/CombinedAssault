package com.wf.combinedassault.client.render.animation.graph.node;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.client.render.animation.graph.WeightSource;
import com.wf.combinedassault.client.render.animation.util.PoseBlenders;

public class BlendNode implements PoseNode {

    private final PoseNode nodeA;
    private final PoseNode nodeB;
    private final WeightSource weightSource;

    public BlendNode(PoseNode nodeA, PoseNode nodeB, WeightSource weightSource) {
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.weightSource = weightSource;
    }

    /** Null input: returns the other pose unchanged. */
    @Override
    public Pose evaluate(IAnimationInstance<?> context) {
        Pose poseA = nodeA.evaluate(context);
        Pose poseB = nodeB.evaluate(context);

        if (poseA == null) return poseB;
        if (poseB == null) return poseA;

        float weight = Math.max(0.0f, Math.min(1.0f, weightSource.getWeight(context)));
        return PoseBlenders.INTERPOLATOR_BLENDER.blend(poseA, poseB, weight);
    }

}
