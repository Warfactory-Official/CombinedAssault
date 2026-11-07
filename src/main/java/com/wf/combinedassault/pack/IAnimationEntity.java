package com.wf.combinedassault.pack;

import com.wf.combinedassault.render.animation.context.EntityContext;
import net.minecraft.world.entity.Entity;

public interface IAnimationEntity<E extends Entity, CTX extends EntityContext<E>> {
    IAnimationInstance<CTX> getAnimationInstance();
}
