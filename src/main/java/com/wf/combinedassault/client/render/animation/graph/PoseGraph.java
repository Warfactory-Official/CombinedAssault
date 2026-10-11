package com.wf.combinedassault.client.render.animation.graph;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.client.render.animation.graph.node.PoseNode;

public class PoseGraph {
    private final PoseNode rootNode;

    public PoseGraph(PoseNode rootNode) {
        this.rootNode = rootNode;
    }

    public Pose evaluate(IAnimationInstance<?> context) {
        if (rootNode == null) {
            return null;
        }
        return rootNode.evaluate(context);
    }

    public PoseNode getRootNode() {
        return rootNode;
    }
}
