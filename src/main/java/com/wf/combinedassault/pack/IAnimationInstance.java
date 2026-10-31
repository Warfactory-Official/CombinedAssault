package com.wf.combinedassault.pack;

import com.maydaymemory.mae.basic.Pose;
import org.jetbrains.annotations.NotNull;
import com.wf.combinedassault.render.animation.context.BaseAnimationContext;

public interface IAnimationInstance<T extends BaseAnimationContext> {

    @NotNull
    T getContext();

    void tick();

    @NotNull
    Pose getCurrentPose();

}
