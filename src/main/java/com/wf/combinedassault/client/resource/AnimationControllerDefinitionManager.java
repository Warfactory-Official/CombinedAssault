package com.wf.combinedassault.client.resource;

import com.wf.combinedassault.client.resource.animation.AnimationControllerDefinition;
import com.wf.combinedassault.custom.serialize.GsonUtil;

public class AnimationControllerDefinitionManager extends JsonDataManager<AnimationControllerDefinition> {

    public AnimationControllerDefinitionManager() {
        super(AnimationControllerDefinition.class, GsonUtil.GSON, "animation_controllers", "AnimationController");
    }

}
