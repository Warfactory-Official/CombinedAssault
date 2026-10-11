package com.wf.combinedassault.client.render.animation.compiler;

import com.wf.combinedassault.client.model.BoneIndexProvider;
import com.wf.combinedassault.client.render.animation.context.BaseAnimationContext;
import com.wf.combinedassault.client.render.animation.controller.AnimationController;
import com.wf.combinedassault.client.resource.animation.AnimationControllerDefinition;

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
