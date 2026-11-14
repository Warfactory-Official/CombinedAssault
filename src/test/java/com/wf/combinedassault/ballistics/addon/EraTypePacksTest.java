package com.wf.combinedassault.ballistics.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wf.wflib.armor.api.hull.EraType;
import com.wf.wflib.armor.api.hull.EraTypes;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EraTypePacksTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final String BUILTIN = "combinedassault_packs/combinedassault/combinedassault/combinedassault/era_types/";

    private static JsonObject shipped(String name) throws IOException {
        try (InputStream in = EraTypePacksTest.class.getClassLoader().getResourceAsStream(BUILTIN + name + ".json")) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
        }
    }

    @Test
    void fieldsParseAndDefault() {
        EraType t = EraTypePacks.parse("ns:x", json("{\"heat_cut\":0.5,\"thickness\":0.2,\"blast_kg\":0.3,"
                + "\"fragments\":{\"count\":4,\"mass_g\":2,\"speed\":800}}"));
        assertEquals(0.5f, t.heatCut());
        assertEquals(0.0f, t.tandemCut());
        assertEquals(76.0f, t.heInitiationMm(), "the old fixed HE threshold is the default");
        assertEquals(4, t.fragments().count());
        assertEquals(null, t.flyer());
    }

    @Test
    void badValuesFailNamingTheType() {
        assertThrows(IllegalArgumentException.class, () -> EraTypePacks.parse("ns:x", json("{\"heat_cut\":1.5}")));
        assertThrows(IllegalArgumentException.class, () -> EraTypePacks.parse("ns:x", json("{\"thickness\":0}")));
        List<String> problems = new ArrayList<>();
        Map<ResourceLocation, JsonObject> defs = new LinkedHashMap<>();
        defs.put(ResourceLocation.fromNamespaceAndPath("ns", "bad"), json("{\"sympathetic\":2}"));
        EraTypePacks.load(defs, problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("ns:bad")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains(EraTypePacks.LIGHT)), "the item types must exist");
    }

    @Test
    void theShippedTypesLoadAndKeepThePreviousConstants() throws IOException {
        Map<ResourceLocation, JsonObject> defs = new LinkedHashMap<>();
        for (String name : new String[] {"era_light", "era_heavy", "kontakt_5", "relikt"}) {
            defs.put(ResourceLocation.fromNamespaceAndPath("combinedassault", name), shipped(name));
        }
        List<String> problems = new ArrayList<>();
        EraTypePacks.load(defs, problems);
        assertEquals(List.of(), problems);
        EraType light = EraTypes.get(EraTypePacks.LIGHT);
        assertEquals(0.65f, light.heatCut());
        assertEquals(0.05f, light.kineticCut());
        assertEquals(20.0f, light.kineticInitiationMm());
        assertEquals(0.10f, light.thickness());
        EraType heavy = EraTypes.get(EraTypePacks.HEAVY);
        assertEquals(0.75f, heavy.heatCut());
        assertEquals(0.35f, heavy.kineticCut());
        assertEquals(35.0f, heavy.kineticInitiationMm());
        assertEquals(0.16f, heavy.thickness());
        assertTrue(EraTypes.get("combinedassault:relikt").tandemCut() > 0);
    }
}
