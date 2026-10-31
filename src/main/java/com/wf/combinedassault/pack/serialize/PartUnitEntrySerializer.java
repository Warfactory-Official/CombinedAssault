package com.wf.combinedassault.pack.serialize;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import com.wf.combinedassault.pack.PackTypes;
import com.wf.combinedassault.pack.part.PartUnitEntry;

import java.lang.reflect.Type;

public class PartUnitEntrySerializer implements JsonDeserializer<PartUnitEntry<?, ?>> {

    @Override
    public PartUnitEntry<?, ?> deserialize(JsonElement ele, Type type, JsonDeserializationContext ctx) throws JsonParseException {
        var obj = GsonHelper.convertToJsonObject(ele, "PartUnit Entry");
        var typeId = obj.get("type").getAsString();
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id == null) {
            throw new JsonParseException("Invalid PartUnit Type id: " + typeId);
        }
        var partUnitType = PackTypes.PART_UNITS.get(id);
        if (partUnitType == null) {
            throw new JsonParseException("Invalid PartUnit Type: " + typeId);
        }
        var entry = partUnitType.parseAndCreate(ele);
        if (entry == null) {
            throw new JsonParseException("Failed to parse data of PartUnit Entry");
        } else {
            return entry;
        }
    }
}
