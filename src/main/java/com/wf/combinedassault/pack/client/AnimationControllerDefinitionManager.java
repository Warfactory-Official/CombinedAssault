package com.wf.combinedassault.pack.client;

import com.wf.combinedassault.pack.client.animation.AnimationControllerDefinition;
import com.wf.combinedassault.pack.serialize.GsonUtil;

public class AnimationControllerDefinitionManager extends JsonDataManager<AnimationControllerDefinition> {

    public AnimationControllerDefinitionManager() {
        super(AnimationControllerDefinition.class, GsonUtil.GSON, "animation_controllers", "AnimationController");
    }

}
