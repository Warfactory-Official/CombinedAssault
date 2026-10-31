package com.wf.combinedassault.pack.serialize;

import com.google.gson.*;
import net.minecraft.util.GsonHelper;
import org.joml.Vector3f;

import java.lang.reflect.Type;

public class Vector3fSerializer implements JsonDeserializer<Vector3f>, JsonSerializer<Vector3f> {
    public Vector3f deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!json.isJsonArray()) {
            throw new JsonSyntaxException("Expected " + json + " to be a Vector3f because it's not an array");
        }
        JsonArray array = json.getAsJsonArray();
        return new Vector3f(GsonHelper.convertToFloat(array.get(0), "(array i=0)"),
                GsonHelper.convertToFloat(array.get(1), "(array i=1)"),
                GsonHelper.convertToFloat(array.get(2), "(array i=2)"));
    }

    public JsonElement serialize(Vector3f src, Type typeOfSrc, JsonSerializationContext context) {
        JsonArray array = new JsonArray(3);
        array.add(src.x());
        array.add(src.y());
        array.add(src.z());
        return array;
    }
}
