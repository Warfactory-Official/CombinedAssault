package com.wf.combinedassault.client.render.animation.graph.node;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.api.animation.IAnimationInstance;

public interface PoseNode {
    /** Null: no pose produced. */
    Pose evaluate(IAnimationInstance<?> context);
}
