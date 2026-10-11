package com.wf.combinedassault.custom.serialize;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.wf.wflib.audio.EngineSound;
import com.wf.wflib.audio.SoundSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.wf.combinedassault.custom.part.PartUnitEntry;
import com.wf.combinedassault.recipe.VehiclePrintingIngredient;
import com.wf.combinedassault.vehicle.pojo.AfterburnerOffset;
import com.wf.combinedassault.vehicle.pojo.WeaponInfo;

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
