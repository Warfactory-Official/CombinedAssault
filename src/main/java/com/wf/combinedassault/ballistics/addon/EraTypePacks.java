package com.wf.combinedassault.ballistics.addon;

import com.google.gson.JsonObject;
import com.wf.wflib.armor.api.hull.EraType;
import com.wf.wflib.armor.api.hull.EraTypes;
import com.wf.wflib.explosion.api.BlastPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code combinedassault/<ns>/era_types/<path>.json} into wflib {@link EraTypes}; id = {@code <ns>:<path>}. */
public final class EraTypePacks {

    public static final String LIGHT = "combinedassault:era_light";
    public static final String HEAVY = "combinedassault:era_heavy";

    private EraTypePacks() {
    }

    /** Replaces the registry; every bad def named in {@code problems}. */
    public static void load(Map<ResourceLocation, JsonObject> defs, List<String> problems) {
        List<EraType> types = new ArrayList<>();
        defs.forEach((id, json) -> {
            try {
                types.add(parse(id.toString(), json));
            } catch (RuntimeException e) {
                problems.add("era type " + id + ": " + e.getMessage());
            }
        });
        EraTypes.replaceAll(types);
        for (String builtin : new String[] {LIGHT, HEAVY}) {
            if (!EraTypes.contains(builtin)) {
                problems.add("era type " + builtin + " missing: items need it");
            }
        }
    }

    public static EraType parse(String id, JsonObject json) {
        return new EraType(id,
                GsonHelper.getAsFloat(json, "heat_cut", 0.0f),
                GsonHelper.getAsFloat(json, "kinetic_cut", 0.0f),
                GsonHelper.getAsFloat(json, "tandem_cut", 0.0f),
                GsonHelper.getAsFloat(json, "kinetic_initiation_mm", 20.0f),
                GsonHelper.getAsFloat(json, "he_initiation_mm", 76.0f),
                GsonHelper.getAsFloat(json, "fragment_initiation_j", 4000.0f),
                GsonHelper.getAsFloat(json, "thickness", 0.10f),
                GsonHelper.getAsFloat(json, "back_plate_mm", 0.0f),
                GsonHelper.getAsFloat(json, "sympathetic", 0.0f),
                GsonHelper.getAsFloat(json, "blast_kg", 0.1f),
                fragments(json, "fragments"), fragments(json, "flyer"));
    }

    @Nullable
    private static BlastPayload.Fragments fragments(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }
        JsonObject f = GsonHelper.getAsJsonObject(json, key);
        return new BlastPayload.Fragments(GsonHelper.getAsInt(f, "count"), GsonHelper.getAsFloat(f, "mass_g"),
                GsonHelper.getAsFloat(f, "speed"));
    }
}
