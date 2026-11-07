package com.wf.combinedassault.pack;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.render.animation.context.BaseAnimationContext;
import org.jetbrains.annotations.NotNull;

public interface IAnimationInstance<T extends BaseAnimationContext> {

    @NotNull
    T getContext();

    void tick();

    @NotNull
    Pose getCurrentPose();

}
