package com.wf.combinedassault.labs.gametest;

import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.labs.VehicleStage;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.ScenarioStage;
import com.wf.wflib.labs.api.ScenarioState;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/** The {@code vehicle} scenario stage on a humvee plate: speed monotonicity, spec validation. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleStageGameTest {

    private static final String TEMPLATE = "empty";
    private static final String ROUND = "wflib:12.7x99/ball";

    private static JsonObject spec(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }

    private static ScenarioStage.Result run(GameTestHelper helper, double speed, JsonObject spec) {
        KineticPreset preset = KineticPresets.get(ResourceLocation.parse(ROUND));
        LabContext ctx = LabContext.of(helper.getLevel(), new double[]{100}, ROUND::equals, Map.of());
        return new VehicleStage().apply(ctx, new ScenarioState("test", ROUND, preset, speed, 1, 0), spec);
    }

    @GameTest(template = TEMPLATE)
    public static void speedOutRisesWithSpeed(GameTestHelper helper) {
        JsonObject spec = spec("vehicle", "combinedassault:humvee", "face", "side");
        double last = -1;
        boolean stopped = false, perforated = false;
        for (double v = 50; v <= 1000; v += 50) {
            ScenarioStage.Result r = run(helper, v, spec);
            helper.assertTrue(r.speedOut() >= last, "speedOut fell at " + v + " m/s: " + last + " -> " + r.speedOut() + " " + r);
            helper.assertTrue(r.speedOut() <= v, "speedOut above speed at " + v + ": " + r);
            helper.assertTrue(r.pPass() == (r.speedOut() > 0 ? 1 : 0), "pPass vs speedOut at " + v + ": " + r);
            stopped |= r.pPass() == 0;
            perforated |= r.pPass() == 1;
            last = r.speedOut();
        }
        helper.assertTrue(stopped && perforated, "sweep never crossed the plate limit, stopped=" + stopped + " perforated=" + perforated);
        helper.assertTrue(((Number) run(helper, 900, spec).detail().get("ke_mm")).doubleValue() == 10, "humvee side plate is 10 mm");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void grazingAngleRicochets(GameTestHelper helper) {
        ScenarioStage.Result r = run(helper, 300, spec("vehicle", "combinedassault:humvee", "face", "side", "angle", "80"));
        helper.assertTrue(r.verdict().equals("RICOCHET") && r.speedOut() == 0, "80 deg should ricochet: " + r);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void badSpecIsRejected(GameTestHelper helper) {
        for (JsonObject bad : new JsonObject[]{
                spec("vehicle", "combinedassault:nope", "face", "side"),
                spec("vehicle", "combinedassault:humvee", "face", "left"),
                spec("vehicle", "combinedassault:humvee", "face", "side", "samples", "4"),
                spec("vehicle", "combinedassault:humvee", "face", "side", "part", "door")}) {
            try {
                run(helper, 500, bad);
            } catch (IllegalArgumentException e) {
                continue;
            }
            helper.fail("accepted " + bad);
            return;
        }
        helper.succeed();
    }
}
