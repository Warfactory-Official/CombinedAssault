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
