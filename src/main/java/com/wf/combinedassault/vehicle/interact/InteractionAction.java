package com.wf.combinedassault.vehicle.interact;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** One action of a def {@code interactions} entry; built by its {@link InteractionTypes} factory at load. */
public interface InteractionAction {

    /** Client: rows offered now. {@code arg} comes back to {@link #apply}. */
    void offer(InteractionContext ctx, Rows out);

    /** Server, after sight + reach checks; re-validates itself. @return true if performed */
    boolean apply(InteractionContext ctx, int arg);

    /** Client: false => offers nothing now; a node none of whose actions is available is not shown. */
    default boolean available(InteractionContext ctx) {
        return true;
    }

    /** Offered to riders of this vehicle (free look); else on foot only. */
    default boolean riders() {
        return false;
    }

    /** Part ids whose generated door node this action replaces. */
    default Set<String> claims() {
        return Set.of();
    }

    @FunctionalInterface
    interface Rows {
        /** {@code arg} in 0..0xFFFF; {@code refusal} non-null => shown greyed with it. */
        void add(int arg, Component label, @Nullable Component refusal);
    }
}
