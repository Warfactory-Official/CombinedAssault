package com.wf.combinedassault.client.render.animation.context;

import net.minecraft.world.entity.Entity;

public class EntityContext<T extends Entity> extends BaseAnimationContext {

    protected final T entity;

    public EntityContext(T entity) {
        this.entity = entity;
    }

    public T getEntity() {
        return entity;
    }

}
