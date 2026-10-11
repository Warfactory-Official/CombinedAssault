package com.wf.combinedassault.api.animation;

import com.maydaymemory.mae.basic.Pose;
import org.jetbrains.annotations.NotNull;
import com.wf.combinedassault.client.render.animation.context.BaseAnimationContext;

public interface IAnimationInstance<T extends BaseAnimationContext> {

    @NotNull
    T getContext();

    void tick();

    @NotNull
    Pose getCurrentPose();

}
