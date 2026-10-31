package com.wf.combinedassault.render.animation.compiler;

import com.wf.combinedassault.render.model.BoneIndexProvider;
import com.wf.combinedassault.render.animation.context.BaseAnimationContext;
import com.wf.combinedassault.render.animation.controller.AnimationController;
import com.wf.combinedassault.pack.client.animation.AnimationControllerDefinition;

public class AnimationControllerCompiler {

    private final PoseGraphCompiler poseGraphCompiler;

    public AnimationControllerCompiler(PoseGraphCompiler.ScriptNodeResolver scriptNodeResolver,
                                       BoneIndexProvider boneIndexProvider) {
        this.poseGraphCompiler = new PoseGraphCompiler(scriptNodeResolver, boneIndexProvider);
    }

    public <T extends BaseAnimationContext> AnimationController<T> compile(AnimationControllerDefinition definition) {
        return new AnimationController<>(definition.getName(), definition.getParameters(),
                definition.getGraph() == null ? null : poseGraphCompiler.compile(definition.getGraph()));
    }
}
