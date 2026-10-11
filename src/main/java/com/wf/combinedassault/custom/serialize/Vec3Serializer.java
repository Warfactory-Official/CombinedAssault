package com.wf.combinedassault.custom.serialize;

import com.google.gson.*;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;

public class Vec3Serializer implements JsonDeserializer<Vec3>, JsonSerializer<Vec3> {
    public Vec3 deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            JsonElement xElement = array.get(0);
            JsonElement yElement = array.get(1);
            JsonElement zElement = array.get(2);
            double x = GsonHelper.convertToDouble(xElement, "(array i=0)");
            double y = GsonHelper.convertToDouble(yElement, "(array i=1)");
            double z = GsonHelper.convertToDouble(zElement, "(array i=2)");
            return new Vec3(x, y, z);
        } else {
            throw new JsonSyntaxException("Expected " + String.valueOf(json) + " to be a Vec3 because it's not an array");
        }
    }

    public JsonElement serialize(Vec3 src, Type typeOfSrc, JsonSerializationContext context) {
        JsonArray array = new JsonArray(3);
        array.add(src.x());
        array.add(src.y());
        array.add(src.z());
        return array;
    }

}