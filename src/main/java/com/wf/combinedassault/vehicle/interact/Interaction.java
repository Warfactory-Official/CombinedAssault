package com.wf.combinedassault.vehicle.interact;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Def {@code interactions} entry: one probe node per model node, at its pose (clips, part motion), sighted
 * ({@code VehicleHits.probeReaches}) with nothing excluded => a handle is seen only from its side of the leaf.
 *
 * @param nodes glTF node indices; any one in sight and reach serves
 * @param name  lang key, null => {@code id} spelled out
 */
public record Interaction(String id, List<Integer> nodes, @Nullable String name, List<InteractionAction> actions) {
}
