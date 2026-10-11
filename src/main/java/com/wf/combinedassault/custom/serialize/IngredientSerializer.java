package com.wf.combinedassault.custom.serialize;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Type;

public class IngredientSerializer implements JsonDeserializer<Ingredient> {

    @Override
    public Ingredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx) throws JsonParseException {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            return Ingredient.CODEC.parse(JsonOps.INSTANCE, entry(json.getAsString()))
                    .getOrThrow(JsonParseException::new);
        }
        if (json.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : json.getAsJsonArray()) {
                out.add(e.isJsonPrimitive() ? entry(e.getAsString()) : e);
            }
            json = out;
        }
        return Ingredient.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(JsonParseException::new);
    }

    /** "#ns:path" => tag (vanilla convention); weapon's accepted-ammo tag stays extensible by packs/mods. */
    private static JsonObject entry(String id) {
        JsonObject obj = new JsonObject();
        if (id.startsWith("#")) {
            obj.addProperty("tag", id.substring(1));
        } else {
            obj.addProperty("item", id);
        }
        return obj;
    }

}
