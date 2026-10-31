package com.wf.combinedassault.render.animation.graph;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.pack.IAnimationInstance;
import com.wf.combinedassault.render.animation.graph.node.PoseNode;

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
