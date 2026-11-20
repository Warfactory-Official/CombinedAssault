package com.wf.combinedassault.vehicle.sightline.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.sightline.CamAudit;
import com.wf.combinedassault.vehicle.sightline.Sightlines;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every camera of every loaded vehicle ({@code -PoptionalPacks} adds optional packs; {@code -PoptionalOnly} filters
 * paths) within its {@link Sightlines.Role} limit. Results -> {@code camaudit.ndjson} in the run dir.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SightlineGameTest {

    /** Worst pose as measured; a camera may only improve. Key: {@code <vehicle id> <part> <camera>}. */
    private record Known(int centre, float frac) {
    }

    private static final Map<String, Known> KNOWN = Map.ofEntries(
            Map.entry("combinedassault:ah_6 sighting_system view", new Known(0, 0.291f)),
            Map.entry("combinedassault:amazog sighting_system sight", new Known(45, 0.147f)),
            Map.entry("combinedassault:bob_semple turret view", new Known(0, 0.328f)),
            Map.entry("combinedassault:bob_semple turret sight", new Known(15, 0.233f)),
            Map.entry("combinedassault:centauro mg_l view", new Known(0, 0.359f)),
            Map.entry("combinedassault:centauro mg_l sight", new Known(169, 0.290f)),
            Map.entry("combinedassault:centauro mg_r view", new Known(0, 0.412f)),
            Map.entry("combinedassault:centauro mg_r sight", new Known(165, 0.215f)),
            Map.entry("combinedassault:centauro driver periscope", new Known(0, 0.110f)),
            Map.entry("combinedassault:centauro driver_out view", new Known(0, 0.260f)),
            Map.entry("combinedassault:ch47 pilot view", new Known(0, 0.305f)),
            Map.entry("combinedassault:ch47 pilot sight", new Known(60, 0.198f)),
            Map.entry("combinedassault:ch47 door_gun_r view", new Known(0, 1.000f)),
            Map.entry("combinedassault:ch47 door_gun_r sight", new Known(67, 0.829f)),
            Map.entry("combinedassault:ch47 door_gun_l view", new Known(0, 1.000f)),
            Map.entry("combinedassault:ch47 door_gun_l sight", new Known(240, 1.000f)),
            Map.entry("combinedassault:ch47 ramp_gun view", new Known(0, 0.543f)),
            Map.entry("combinedassault:ch47 ramp_gun sight", new Known(94, 0.415f)),
            Map.entry("combinedassault:f22a sighting_system sight", new Known(0, 0.188f)),
            Map.entry("combinedassault:flak36 gun sight", new Known(8, 0.120f)),
            Map.entry("combinedassault:humvee driver view", new Known(0, 0.513f)),
            Map.entry("combinedassault:humvee roof_mount view", new Known(0, 0.422f)),
            Map.entry("combinedassault:humvee roof_mount sight", new Known(240, 0.851f)),
            Map.entry("combinedassault:kilo helm view", new Known(0, 0.146f)),
            Map.entry("combinedassault:kilo helm sight", new Known(70, 0.414f)),
            Map.entry("combinedassault:krupp_c34 gun sight", new Known(0, 0.074f)),
            Map.entry("combinedassault:laser_emplacement laser sight", new Known(0, 0.241f)),
            Map.entry("combinedassault:laser_trailer laser sight", new Known(0, 0.241f)),
            Map.entry("combinedassault:m1129 mortar sight", new Known(0, 0.085f)),
            Map.entry("combinedassault:m1129 mg view", new Known(0, 0.405f)),
            Map.entry("combinedassault:m1129 mg sight", new Known(20, 0.249f)),
            Map.entry("combinedassault:m113 commander periscope", new Known(56, 0.776f)),
            Map.entry("combinedassault:m113 mg view", new Known(0, 1.000f)),
            Map.entry("combinedassault:m113 mg sight", new Known(205, 0.469f)),
            Map.entry("combinedassault:m142 driver view", new Known(0, 0.962f)),
            Map.entry("combinedassault:m142 launcher sight", new Known(0, 0.138f)),
            Map.entry("combinedassault:m1a2 turret sight", new Known(1, 0.267f)),
            Map.entry("combinedassault:m1a2 anti_aircraft_machine_gun sight", new Known(29, 0.317f)),
            Map.entry("combinedassault:m1a2 loader_machine_gun view", new Known(0, 0.670f)),
            Map.entry("combinedassault:m1a2 loader_machine_gun sight", new Known(240, 0.773f)),
            Map.entry("combinedassault:m1a2 seat periscope", new Known(0, 0.167f)),
            Map.entry("combinedassault:m1a2 commander_out view", new Known(0, 0.201f)),
            Map.entry("combinedassault:m777 gun view", new Known(0, 0.543f)),
            Map.entry("combinedassault:m777 gun sight", new Known(201, 0.586f)),
            Map.entry("combinedassault:m983 seat0 view", new Known(0, 0.625f)),
            Map.entry("combinedassault:meko_a200 main_gun view", new Known(0, 0.409f)),
            Map.entry("combinedassault:meko_a200 main_gun sight", new Known(240, 1.000f)),
            Map.entry("combinedassault:merkava turret sight", new Known(3, 0.079f)),
            Map.entry("combinedassault:merkava anti_aircraft_machine_gun view", new Known(0, 0.291f)),
            Map.entry("combinedassault:merkava anti_aircraft_machine_gun sight", new Known(240, 1.000f)),
            Map.entry("combinedassault:merkava seat periscope", new Known(0, 0.117f)),
            Map.entry("combinedassault:merkava commander periscope", new Known(240, 0.957f)),
            Map.entry("combinedassault:mh60l sighting_system sight", new Known(7, 0.227f)),
            Map.entry("combinedassault:mh60l door_gun_r view", new Known(0, 0.693f)),
            Map.entry("combinedassault:mh60l door_gun_r sight", new Known(240, 1.000f)),
            Map.entry("combinedassault:mh60l door_gun_l view", new Known(0, 0.636f)),
            Map.entry("combinedassault:mh60l door_gun_l sight", new Known(240, 1.000f)),
            Map.entry("combinedassault:mv22 pilot view", new Known(0, 0.194f)),
            Map.entry("combinedassault:mv22 pilot sight", new Known(0, 0.471f)),
            Map.entry("combinedassault:narcosub helm view", new Known(0, 1.000f)),
            Map.entry("combinedassault:osa helm view", new Known(0, 0.378f)),
            Map.entry("combinedassault:osa helm sight", new Known(100, 0.468f)),
            Map.entry("combinedassault:osa gun_fwd view", new Known(0, 1.000f)),
            Map.entry("combinedassault:osa gun_fwd sight", new Known(240, 1.000f)),
            Map.entry("combinedassault:osa gun_aft view", new Known(0, 1.000f)),
            Map.entry("combinedassault:osa gun_aft sight", new Known(38, 0.068f)),
            Map.entry("combinedassault:oto127 gun sight", new Known(2, 0.039f)),
            Map.entry("combinedassault:riverine helm view", new Known(0, 0.644f)),
            Map.entry("combinedassault:riverine gun_bow_port view", new Known(0, 0.402f)),
            Map.entry("combinedassault:riverine gun_bow_port sight", new Known(205, 0.316f)),
            Map.entry("combinedassault:riverine gun_bow_starboard view", new Known(0, 0.417f)),
            Map.entry("combinedassault:riverine gun_bow_starboard sight", new Known(205, 0.386f)),
            Map.entry("combinedassault:riverine gun_stern view", new Known(0, 0.367f)),
            Map.entry("combinedassault:riverine gun_stern sight", new Known(9, 0.180f)),
            Map.entry("combinedassault:spg9 gun sight", new Known(0, 0.073f)),
            Map.entry("combinedassault:spg9 sight sight", new Known(0, 0.073f)),
            Map.entry("combinedassault:su33 sighting_system sight", new Known(4, 0.361f)),
            Map.entry("combinedassault:su47 sighting_system sight", new Known(0, 0.181f)),
            Map.entry("combinedassault:t84 turret sight", new Known(17, 0.440f)),
            Map.entry("combinedassault:t84 seat periscope", new Known(0, 0.153f)),
            Map.entry("combinedassault:t84 commander periscope", new Known(240, 1.000f)),
            Map.entry("combinedassault:t84 commander_out view", new Known(0, 0.285f)),
            Map.entry("combinedassault:t90 turret sight", new Known(0, 0.062f)),
            Map.entry("combinedassault:t90 seat periscope", new Known(0, 0.142f)),
            Map.entry("combinedassault:t90 commander periscope", new Known(140, 0.623f)),
            Map.entry("combinedassault:t90 driver_out view", new Known(0, 0.207f)),
            Map.entry("combinedassault:t90 gunner_out view", new Known(0, 0.233f)),
            Map.entry("combinedassault:visby gun_57mm view", new Known(0, 0.500f)),
            Map.entry("combinedassault:visby gun_57mm sight", new Known(240, 0.752f)),
            Map.entry("combinedassault:w3 sighting_system sight", new Known(12, 0.419f)),
            Map.entry("combinedassault:w3 nose_gun view", new Known(0, 0.962f)),
            Map.entry("combinedassault:w3 nose_gun sight", new Known(240, 0.850f)),
            Map.entry("combinedassault:w3 door_gun view", new Known(0, 0.800f)),
            Map.entry("combinedassault:w3 door_gun sight", new Known(221, 0.847f))
    );

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void everyCameraSeesPastItsOwnVehicle(GameTestHelper helper) {
        String only = System.getProperty("combinedassault.optionalPacks.only", "");
        Set<String> keep = only.isEmpty() ? Set.of() : Set.of(only.split(","));
        List<ResourceLocation> ids = new ArrayList<>(CommonAssetsManager.vehicleDataManager().getVehicleData().keySet()
                .stream().filter(id -> keep.isEmpty() || keep.contains(id.getPath())).sorted().toList());
        List<String> problems = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] cameras = new int[1];
        var sequence = helper.startSequence();
        for (ResourceLocation id : ids) {
            sequence = sequence.thenExecute(() -> {
                List<Sightlines.Result> results;
                try {
                    results = CamAudit.of(helper.getLevel(), id);
                } catch (RuntimeException e) {
                    problems.add(id + ": " + e);
                    CombinedAssault.LOGGER.error("[camaudit] {}", id, e);
                    return;
                }
                for (Sightlines.Result r : results) {
                    cameras[0]++;
                    String key = id + " " + r.part() + " " + r.camera();
                    seen.add(key);
                    lines.add(GSON.toJson(Map.of("vehicle", id.toString(), "result", r)));
                    CombinedAssault.LOGGER.info("[camaudit] {} {}", id, r.line());
                    Known known = KNOWN.get(key);
                    if (known == null) {
                        if (!r.pass()) {
                            problems.add(id + " " + r.line());
                        }
                    } else if (r.pass()) {
                        problems.add(key + " passes now: drop it from KNOWN");
                    } else if (r.centre() > known.centre || r.frac() > known.frac + 0.005f) {
                        problems.add(String.format(Locale.ROOT, "%s regressed from centre %d frac %.3f: %s", key,
                                known.centre, known.frac, r.line()));
                    }
                }
            }).thenIdle(1);
        }
        sequence.thenExecute(() -> {
            try {
                Files.write(Path.of("camaudit.ndjson"), lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String key : KNOWN.keySet()) {
                ResourceLocation id = ResourceLocation.parse(key.substring(0, key.indexOf(' ')));
                if (ids.contains(id) && !seen.contains(key)) {
                    problems.add(key + " no such camera: drop it from KNOWN");
                }
            }
            CombinedAssault.LOGGER.info("[camaudit] {} vehicles, {} cameras, {} problems", ids.size(), cameras[0],
                    problems.size());
            if (!problems.isEmpty()) {
                helper.fail(problems.size() + " camera problems: " + String.join("; ", problems));
            }
        }).thenSucceed();
    }
}
