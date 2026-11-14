package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.Ballistics;
import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.addon.IntegralEra;
import com.wf.combinedassault.ballistics.gametest.SmallArmsArmorGameTest.Aim;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Integral ERA arrays on the T-90 plates ({@code ufp}: 28 Kontakt-5 bricks): walked, spent once, saved, stripped by HE. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class ArmorEraGameTest {

    private static final String TEMPLATE = "empty";
    private static final String BATCH = "armorera";
    private static final ResourceLocation T90 = CombinedAssault.modLocation("t90");
    private static final ResourceLocation DART_120 = ResourceLocation.parse("wflib:120mm/apfsds");
    private static final ResourceLocation HE_120 = ResourceLocation.parse("wflib:120mm/he");
    private static final ResourceLocation HE_30 = ResourceLocation.parse("wflib:30x173/he");
    private static final String UFP = "ufp";
    private static final int UFP_CELLS = 28;
    /** Past the lanes of {@code WalkGameTest} (20) and {@code BlastArmorGameTest} (30 to 41). */
    private static final int LANE = 50;

    private static final Threat JET = new Threat(ThreatKind.HEAT, 85, 900);
    private static final Threat TANDEM = new Threat(ThreatKind.TANDEM_HEAT, 85, 900);
    private static final Threat ROD = new Threat(ThreatKind.KINETIC, 120, 2000, 1.0f, 90, 1.0f, 0, 0);

    /** Shot at {@code aim} {@code incidence} deg off its normal, tilted horizontally, from 3 m. */
    private static Vec3 direction(Aim aim, double incidence) {
        Vec3 tangent = aim.normal().cross(new Vec3(0, 1, 0)).normalize();
        double a = Math.toRadians(incidence);
        return aim.normal().scale(-Math.cos(a)).add(tangent.scale(Math.sin(a))).normalize();
    }

    private static HullResult resolve(AbstractVehicle v, Aim aim, Vec3 dir, Threat threat) {
        HullResult r = Ballistics.blast(v, aim.point().subtract(dir.scale(3)), dir, aim.point(), threat, 100.0f, null);
        if (r == null) {
            throw new AssertionError("no armour answer at " + aim.point());
        }
        return r;
    }

    /** A 60 deg jet gets the full obliquity effect: Kontakt-5 keeps half of it. The spent brick is a hole. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void aJetSpendsTheIntegralBrickItHitsAndTheNextOneGoesThroughMore(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE, T90, (seq, v) -> seq.thenExecute(() -> {
            Aim aim = SmallArmsArmorGameTest.aimPlate(helper, v.get(), UFP);
            Vec3 dir = direction(aim, 60);
            AddonArmorLayer layer = v.get().addonArmor();
            HullResult live = resolve(v.get(), aim, dir, JET);
            helper.assertTrue(live.walk().eraHits().size() == 1 && UFP.equals(live.walk().eraHits().get(0).plate()),
                    "bricks set off by the jet: " + live.walk().eraHits());
            int cell = live.walk().eraHits().get(0).cell();
            helper.assertTrue(layer.arrayLive(UFP, cell), "resolve spent the brick");
            IntegralEra.commit(v.get(), live.walk(), aim.point());
            helper.assertFalse(layer.arrayLive(UFP, cell), "the committed brick is still live");
            helper.assertTrue(layer.arrayLiveCells(UFP, UFP_CELLS) < UFP_CELLS, "no brick spent");
            HullResult hole = resolve(v.get(), aim, dir, JET);
            helper.assertTrue(hole.walk().eraHits().isEmpty(), "the spent brick set off again");
            float gained = hole.interiorPen() - live.interiorPen();
            helper.assertTrue(Math.abs(gained - 450.0f) < 5.0f,
                    "behind the hole " + hole.interiorPen() + " vs behind the brick " + live.interiorPen()
                            + ": the brick should have cost half of 900 mm, got " + gained);
        }));
    }

    /** Kontakt class: the precursor spends the brick, the main jet passes untouched. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void aTandemPrecursorSpendsTheBrickAndTheMainJetIsUncut(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 1, T90, (seq, v) -> seq.thenExecute(() -> {
            Aim aim = SmallArmsArmorGameTest.aimPlate(helper, v.get(), UFP);
            Vec3 dir = direction(aim, 60);
            HullResult live = resolve(v.get(), aim, dir, TANDEM);
            helper.assertTrue(live.walk().eraHits().size() == 1, "the precursor set off " + live.walk().eraHits());
            IntegralEra.commit(v.get(), live.walk(), aim.point());
            HullResult hole = resolve(v.get(), aim, dir, TANDEM);
            helper.assertTrue(Math.abs(hole.interiorPen() - live.interiorPen()) < 0.5f,
                    "tandem behind the brick " + live.interiorPen() + ", behind the hole " + hole.interiorPen());
        }));
    }

    /** Fully live the plate needs 1 / (1 - 0.3) of what the bare one does against a rod at the declared slope. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void theKontaktShareLivesInTheBricksNotInTheFace(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 2, T90, (seq, v) -> seq.thenExecute(() -> {
            Aim aim = SmallArmsArmorGameTest.aimPlate(helper, v.get(), UFP);
            Vector3f forward = new Quaternionf(v.get().rotYXZ()).transform(new Vector3f(0, 0, -1));
            Vec3 dir = new Vec3(forward.x, forward.y, forward.z);
            AddonArmorLayer layer = v.get().addonArmor();
            float whole = resolve(v.get(), aim, dir, ROD).walk().needed();
            for (int c = 0; c < UFP_CELLS; c++) {
                layer.spendArrayCell(UFP, c);
            }
            float bare = resolve(v.get(), aim, dir, ROD).walk().needed();
            helper.assertTrue(Math.abs(whole / bare - 1 / 0.7f) < 0.03f,
                    "needed " + whole + " live, " + bare + " bare: ratio " + whole / bare + ", want " + 1 / 0.7f);
            layer.refillArrays();
            helper.assertTrue(layer.arrayLiveCells(UFP, UFP_CELLS) == UFP_CELLS, "refill left spent bricks");
        }));
    }

    /** A real round through the strike event and {@code DamageSystem}: the dart's hit is final, the brick goes once. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void aDartOnTheUfpSpendsBricksThroughTheRealStrikePath(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 3, T90, (seq, v) -> seq
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), DART_120, UFP))
                .thenIdle(6).thenExecute(() -> {
                    int live = v.get().addonArmor().arrayLiveCells(UFP, UFP_CELLS);
                    helper.assertTrue(live < UFP_CELLS && live >= UFP_CELLS - 9,
                            "the dart left " + live + " of " + UFP_CELLS + " bricks (one + at most 8 neighbours go)");
                    SmallArmsArmorGameTest.expect(helper, v.get(), "overmatch", "penetration", "partial", "absorbed");
                }));
    }

    /** HE strips an array from its type's HE initiation up: 30 mm leaves Kontakt-5 alone, a 120 mm shell takes a brick. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void aBigHeShellStripsAnIntegralBrickAndAnAutocannonShellDoesNot(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 4, T90, (seq, v) -> seq
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), HE_30, UFP))
                .thenIdle(8).thenExecute(() -> helper.assertTrue(
                        v.get().addonArmor().arrayLiveCells(UFP, UFP_CELLS) == UFP_CELLS,
                        "30 mm HE set off a Kontakt-5 brick"))
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), HE_120, UFP))
                .thenIdle(8).thenExecute(() -> helper.assertTrue(
                        v.get().addonArmor().arrayLiveCells(UFP, UFP_CELLS) < UFP_CELLS,
                        "120 mm HE left every brick live")));
    }

    /** Spent bricks are saved with the vehicle and ride the sync tag. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void spentIntegralBricksSurviveSaveAndLoad(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 5, T90, (seq, v) -> seq.thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            vehicle.addonArmor().markClean();
            vehicle.addonArmor().spendArrayCell(UFP, 3);
            vehicle.addonArmor().spendArrayCell("turret_front", 1);
            helper.assertTrue(vehicle.addonArmor().isDirty(), "a spent brick did not mark the layer for sync");
            CompoundTag saved = vehicle.saveWithoutId(new CompoundTag());
            AbstractVehicle copy = CommonAssetsManager.vehicleDataManager().getVehicleData(T90).orElseThrow()
                    .construct(vehicle.level(), vehicle.position(), 0, 0);
            copy.load(saved);
            helper.assertTrue(!copy.addonArmor().arrayLive(UFP, 3) && !copy.addonArmor().arrayLive("turret_front", 1)
                            && copy.addonArmor().arrayLive(UFP, 4),
                    "reloaded vehicle bricks: ufp live 3/4 = " + copy.addonArmor().arrayLive(UFP, 3) + "/"
                            + copy.addonArmor().arrayLive(UFP, 4));
            AbstractVehicle synced = CommonAssetsManager.vehicleDataManager().getVehicleData(T90).orElseThrow()
                    .construct(vehicle.level(), vehicle.position(), 0, 0);
            synced.addonArmor().load(vehicle.addonArmor().save());
            helper.assertTrue(!synced.addonArmor().arrayLive(UFP, 3), "the sync tag lost the spent brick");
        }));
    }
}
