package com.wf.combinedassault.render.animation.graph.node;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.pack.IAnimationInstance;

public interface PoseNode {
    /** Null: no pose produced. */
    Pose evaluate(IAnimationInstance<?> context);
}
