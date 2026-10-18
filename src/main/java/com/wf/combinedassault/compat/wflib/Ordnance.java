package com.wf.combinedassault.compat.wflib;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CombinedPack;
import com.wf.combinedassault.pack.CombinedPacks;
import com.wf.combinedassault.vehicle.pojo.MissileSize;
import com.wf.wflib.MissileModels;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.content.WFLibContent;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.missile.SeekerMode;
import com.wf.wflib.ordnance.VehicleOrdnance;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Own vehicle rockets, bombs and missiles ({@code combinedassault:} presets, WFLib items) + WFLib catalogue requests:
 * every {@code wflib:} item / {@code #wflib:} tag a pack def names, every size tag a missile holder accepts, and what
 * the game tests fire. Mod construction, after {@link CombinedPacks#discover}.
 */
public final class Ordnance {

    private Ordnance() {
    }

    public static void register() {
        rockets();
        missiles();
        for (CombinedPack pack : CombinedPacks.packs()) {
            requestPack(pack);
        }
        if (List.of(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
                .contains(CombinedAssault.MOD_ID)) {
            requestTests();
        }
    }

    private static ResourceLocation rl(String path) {
        return CombinedAssault.modLocation(path);
    }

    private static void rockets() {
        for (String id : new String[]{"hydra_70", "crv7"}) {
            KineticPresetRegistry.register(VehicleOrdnance.rocket(rl(id), 70, 11.3, 4520, 10, 0.0565, 100, 3));
        }
        KineticPresetRegistry.register(VehicleOrdnance.rocket(rl("s8"), 80, 11.3, 4520, 10, 0.0565, 100, 3));
        for (String id : new String[]{"mk_82", "fab_250", "cbu_87"}) {
            KineticPresetRegistry.register(VehicleOrdnance.bomb(rl(id), 227, 1.135, 2, 0));
        }
        KineticPresetRegistry.register(VehicleOrdnance.bomb(rl("b61"), 320, 1.6, 64, 0));
    }

    private static void missiles() {
        ThreatKind tandem = ThreatKind.TANDEM_HEAT;
        // SACLOS: rides the launcher's beam.
        for (String id : new String[]{"agm_114", "agm_65", "akd_10", "kh_31", "shturm", "vikhr"}) {
            missile(id, MissileModels.rl("atgm"), tandem, 178, 1000, 5, 0, 49, 11760, 600, 0.245, 18, 3,
                    SeekerMode.BEAM_RIDING);
        }
        missile("tow", MissileModels.rl("atgm"), tandem, 152, 1000, 4, 0, 21.5, 3440, 600, 0.1075, 18, 2,
                SeekerMode.OPTICAL);
        missile("fb_10", MissileModels.rl("guided_rocket"), ThreatKind.HE, 120, 99, 8, 2, 30, 4800, 600, 0.15, 18, 6,
                SeekerMode.SEMI_ACTIVE_RADAR);
        for (String id : new String[]{"aim_9", "aim_132", "magic"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 127, 100, 8, 4, 85, 53400, 40, 0.445, 30, 1,
                    SeekerMode.INFRARED);
        }
        for (String id : new String[]{"r_73", "pl_8"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 170, 100, 8, 4, 115, 46000, 60, 0.575, 30,
                    1, SeekerMode.INFRARED);
        }
        for (String id : new String[]{"aim_120", "mica"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 178, 100, 8, 4, 112, 44800, 300, 0.56, 30,
                    0.5, SeekerMode.ACTIVE_RADAR);
        }
        for (String id : new String[]{"r_77", "pl_12"}) {
            missile(id, MissileModels.rl("guided_rocket"), ThreatKind.HE, 200, 100, 8, 4, 180, 72000, 300, 0.9, 30, 1,
                    SeekerMode.ACTIVE_RADAR);
        }
        MissilePresetRegistry.register(VehicleOrdnance.base(rl("df_41"), MissileModels.rl("atlas"), ThreatKind.HE,
                        2250, 1000, 64, 0, 80000, 6400000, 12000, 400, 8, 5, false)
                .highAltitude(512.0)
                .build());
        for (String id : new String[]{"gbu_12", "gbu_39"}) {
            MissilePresetRegistry.register(VehicleOrdnance.guidedBomb(rl(id), 230, 1.15, 16));
        }
    }

    private static void missile(String id, ResourceLocation model, ThreatKind threat, float calibre, float damage,
                                float blast, float proximity, double mass, double thrust, int burnTicks, double cdA,
                                double maxG, double refSpeed, SeekerMode seeker) {
        MissilePresetRegistry.register(VehicleOrdnance.missile(rl(id), model, threat, calibre, damage, blast,
                proximity, mass, thrust, burnTicks, cdA, maxG, refSpeed, seeker, 30));
    }

    private static void requestPack(CombinedPack pack) {
        Path defs = pack.root().resolve(CombinedPacks.DEFS);
        if (!Files.isDirectory(defs)) {
            return;
        }
        String source = CombinedAssault.MOD_ID + " pack " + pack.id();
        try (Stream<Path> walk = Files.walk(defs)) {
            for (Path f : walk.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                scan(source + " " + defs.relativize(f), JsonParser.parseString(Files.readString(f)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(defs.toString(), e);
        }
    }

    private static void scan(String source, JsonElement e) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> scan(source, x));
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("type") instanceof JsonPrimitive t && t.getAsString().equals("combinedassault:missile_holder")
                    && !o.has("pods")) {
                if (o.has("accepts")) {
                    o.getAsJsonArray("accepts").forEach(a -> sizeTag(source, MissileSize.valueOf(a.getAsString())));
                } else {
                    for (MissileSize size : MissileSize.VALUES) {
                        sizeTag(source, size);
                    }
                }
            }
            o.entrySet().forEach(x -> scan(source, x.getValue()));
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            if (s.startsWith("#wflib:")) {
                WFLibContent.requestTag(source, ResourceLocation.parse(s.substring(1)));
            } else if (s.startsWith("wflib:")) {
                WFLibContent.requestItem(source, ResourceLocation.parse(s));
            }
        }
    }

    private static void sizeTag(String source, MissileSize size) {
        WFLibContent.requestTag(source, size.tag().location());
    }

    private static void requestTests() {
        String source = CombinedAssault.MOD_ID + " game tests";
        WFLibContent.calibres(source, "5.56x45", "7.62x51", "12.7x99", "20x102", "30x29", "40x53", "60mm", "73mm_spg9",
                "81mm", "88x571r", "120mm", "125mm", "rpg7", "vog25");
        WFLibContent.kinetic(source, "heat", "rap");
        WFLibContent.missiles(source, "cruise", "ballistic", "gmlrs", "gmlrs_aw", "atacms", "interceptor_hypersonic");
        WFLibContent.magazines(source, "ammo_can_12_7x99", "pod_gmlrs", "pod_atacms");
    }
}
