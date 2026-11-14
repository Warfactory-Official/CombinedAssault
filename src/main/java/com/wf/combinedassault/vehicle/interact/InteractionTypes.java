package com.wf.combinedassault.vehicle.interact;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Action type registry: {@code type} id -> factory. Register before vehicle data loads (mod construction). */
public final class InteractionTypes {

    /** Load-time view of the vehicle being built. */
    public interface Def {
        Set<String> partIds();

        List<String> entries();
    }

    @FunctionalInterface
    public interface Factory {
        /** @param params the action object minus {@code type}; unknown key => throw */
        InteractionAction create(JsonObject params, Def def);
    }

    private static final Map<ResourceLocation, Factory> TYPES = new ConcurrentHashMap<>();

    static {
        register(CombinedAssault.modLocation("toggle"), BuiltinActions::toggle);
        register(CombinedAssault.modLocation("board"), BuiltinActions::board);
        register(CombinedAssault.modLocation("storage"), BuiltinActions::storage);
        register(CombinedAssault.modLocation("refuel"), RefuelAction::create);
    }

    private InteractionTypes() {
    }

    public static void register(ResourceLocation id, Factory factory) {
        if (TYPES.putIfAbsent(id, factory) != null) {
            throw new IllegalStateException("interaction type " + id + " registered twice");
        }
    }

    /** Def {@code interactions} -> bound entries; node names must name exactly one glTF node. */
    public static List<Interaction> parse(Map<String, JsonObject> raw, ModelMounts mounts, Def def) {
        List<Interaction> out = new ArrayList<>();
        for (var entry : raw.entrySet()) {
            String id = entry.getKey();
            JsonObject json = entry.getValue();
            for (String key : json.keySet()) {
                if (!Set.of("node", "name", "actions").contains(key)) {
                    throw new IllegalArgumentException("interaction " + id + ": unknown key " + key);
                }
            }
            if (!json.has("node") || !json.has("actions")) {
                throw new IllegalArgumentException("interaction " + id + " needs node and actions");
            }
            JsonElement named = json.get("node");
            List<Integer> nodes = new ArrayList<>();
            for (JsonElement element : named.isJsonArray() ? named.getAsJsonArray() : List.of(named)) {
                String node = element.getAsString();
                int index = -1;
                for (int i = 0; i < mounts.links().size(); i++) {
                    if (mounts.links().get(i).name().equals(node)) {
                        if (index >= 0) {
                            throw new IllegalArgumentException("interaction " + id + ": node " + node + " is not unique");
                        }
                        index = i;
                    }
                }
                if (index < 0) {
                    throw new IllegalArgumentException("interaction " + id + ": no model node " + node);
                }
                nodes.add(index);
            }
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("interaction " + id + ": empty node list");
            }
            List<InteractionAction> actions = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("actions")) {
                JsonObject action = element.getAsJsonObject().deepCopy();
                if (!action.has("type")) {
                    throw new IllegalArgumentException("interaction " + id + ": action without type");
                }
                ResourceLocation type = ResourceLocation.parse(action.remove("type").getAsString());
                Factory factory = TYPES.get(type);
                if (factory == null) {
                    throw new IllegalArgumentException("interaction " + id + ": unknown action type " + type
                            + "; known " + TYPES.keySet());
                }
                try {
                    actions.add(factory.create(action, def));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("interaction " + id + ": " + type + ": " + e.getMessage(), e);
                }
            }
            if (actions.isEmpty() || actions.size() > 0xFF || out.size() >= 0x7F) {
                throw new IllegalArgumentException("interaction " + id + ": 1..255 actions, < 128 interactions");
            }
            out.add(new Interaction(id, List.copyOf(nodes), json.has("name") ? json.get("name").getAsString() : null,
                    List.copyOf(actions)));
        }
        return List.copyOf(out);
    }
}
