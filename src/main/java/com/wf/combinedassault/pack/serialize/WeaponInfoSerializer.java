package com.wf.combinedassault.pack.serialize;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import com.wf.combinedassault.weapon.pojo.WeaponInfo;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class WeaponInfoSerializer implements JsonDeserializer<WeaponInfo> {

    public WeaponInfo deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx) throws JsonParseException {
        if (GsonHelper.isStringValue(json)) {
            String str = json.getAsString();
            ResourceLocation id = ResourceLocation.tryParse(str);
            if (id == null) {
                throw new JsonParseException("Invalid weapon info string: " + str);
            }
            String saveId = id.toString();
            return new WeaponInfo(id, null, null, false, false, saveId);
        } else if (json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            String id = GsonHelper.getAsString(obj, "id", null);
            String partUnitId = GsonHelper.getAsString(obj, "part_unit_id", null);
            String weaponBayUnitId = GsonHelper.getAsString(obj, "weapon_bay_unit_id", null);
            boolean secondary = GsonHelper.getAsBoolean(obj, "secondary", false);
            boolean fullSalvo = GsonHelper.getAsBoolean(obj, "full_salvo", false);
            String saveId = GsonHelper.getAsString(obj, "save_id", partUnitId);
            WeaponInfo info = new WeaponInfo(id == null ? null : ResourceLocation.tryParse(id), partUnitId,
                    weaponBayUnitId, secondary, fullSalvo, saveId);
            if (obj.has("kit")) {
                info.kit = ResourceLocation.parse(obj.get("kit").getAsString());
            }
            if (obj.has("round_bones")) {
                List<String> bones = new ArrayList<>();
                for (JsonElement elem : obj.getAsJsonArray("round_bones")) {
                    bones.add(elem.getAsString());
                }
                info.roundBones = List.copyOf(bones);
            }
            return info;
        } else {
            throw new JsonParseException("Invalid weapon info format: " + json);
        }
    }

}
