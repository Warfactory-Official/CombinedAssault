package com.wf.combinedassault.vehicle.parenting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finds the {@link DeckCarrier} behind an entity id.
 * Compat adapter point: e.g. Immersive Railroading stock is a wrapper entity that can't implement this
 * interface, without naming IR's types here.
 * Resolver MUST return the same carrier object per entity every call: harness compares carriers by
 * identity, a fresh adapter per call detaches every vehicle next tick.
 */
public final class DeckCarriers {

    @FunctionalInterface
    public interface Resolver {
        @Nullable
        DeckCarrier resolve(Entity entity);
    }

    private static final List<Resolver> RESOLVERS = new CopyOnWriteArrayList<>();

    private DeckCarriers() {
    }

    public static void register(Resolver resolver) {
        RESOLVERS.add(resolver);
    }

    /** Null when the id names no entity, or that entity carries nothing. */
    @Nullable
    public static DeckCarrier byId(Level level, int entityId) {
        if (entityId < 0) {
            return null;
        }
        Entity entity = level.getEntity(entityId);
        return entity == null ? null : of(entity);
    }

    /** Null when the entity carries nothing. */
    @Nullable
    public static DeckCarrier of(Entity entity) {
        if (entity instanceof DeckCarrier carrier) {
            return carrier;
        }
        for (Resolver resolver : RESOLVERS) {
            DeckCarrier carrier = resolver.resolve(entity);
            if (carrier != null) {
                return carrier;
            }
        }
        return null;
    }
}
