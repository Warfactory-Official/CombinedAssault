package com.wf.combinedassault.api.collision;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Register during mod construction. Copy-on-write: read every tick per vehicle, written essentially never. */
public final class CollisionProviders {

    private static final List<CollisionProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private CollisionProviders() {}

    public static void register(CollisionProvider provider) {
        PROVIDERS.add(provider);
    }

    public static void unregister(CollisionProvider provider) {
        PROVIDERS.remove(provider);
    }

    /** Live view, not a copy. */
    public static List<CollisionProvider> providers() {
        return PROVIDERS;
    }

    public static boolean isEmpty() {
        return PROVIDERS.isEmpty();
    }

}
