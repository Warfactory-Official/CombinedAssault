package com.wf.combinedassault.pack.serialize;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.wf.wflib.audio.api.EngineSound;
import com.wf.wflib.audio.api.SoundSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.wf.combinedassault.pack.part.PartUnitEntry;
import com.wf.combinedassault.vehicle.recipe.VehiclePrintingIngredient;
import com.wf.combinedassault.aircraft.pojo.AfterburnerOffset;
import com.wf.combinedassault.weapon.pojo.WeaponInfo;
import com.wf.combinedassault.aircraft.pojo.AfterburnerOffsetSerializer;

public class GsonUtil {

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ResourceLocation.class, new ResourceLocation.Serializer())
            .registerTypeAdapter(Vec3.class, new Vec3Serializer())
            .registerTypeAdapter(AfterburnerOffset.class, new AfterburnerOffsetSerializer())
            .registerTypeAdapter(Ingredient.class, new IngredientSerializer())
            .registerTypeAdapter(WeaponInfo.class, new WeaponInfoSerializer())
            .registerTypeAdapter(PartUnitEntry.class, new PartUnitEntrySerializer())
            .registerTypeAdapter(Vector3f.class, new Vector3fSerializer())
            .registerTypeAdapter(VehiclePrintingIngredient.class, new VehiclePrintingIngredient.Deserializer())
            .registerTypeAdapter(SoundSpec.class, (JsonDeserializer<SoundSpec>) (j, t, c) -> SoundSpec.fromJson(j))
            .registerTypeAdapter(EngineSound.class, (JsonDeserializer<EngineSound>) (j, t, c) -> EngineSound.fromJson(j))
            .create();

}
