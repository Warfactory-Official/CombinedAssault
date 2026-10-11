package com.wf.combinedassault.api.animation;

import net.minecraft.world.entity.Entity;
import com.wf.combinedassault.client.render.animation.context.EntityContext;

public interface IAnimationEntity<E extends Entity, CTX extends EntityContext<E>> {
    IAnimationInstance<CTX> getAnimationInstance();
}
