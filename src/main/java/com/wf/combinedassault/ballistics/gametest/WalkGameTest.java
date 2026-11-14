package com.wf.combinedassault.ballistics.gametest;

import com.google.gson.Gson;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.gametest.SmallArmsArmorGameTest.Aim;
import com.wf.combinedassault.labs.VehicleLab;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.PlateSpec;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import com.wf.wflib.damage.api.event.ProjectileStrikeEvent;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;

/**
 * Layer walk end to end: air gaps, a round that leaves the hull, a {@code post_pen} round that goes off behind the
 * entry plate. Plates are the shipped ones; the skirt test swaps one declaration for a spaced one.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class WalkGameTest {

    private static final String TEMPLATE = "empty";
    private static final String BATCH = "armorwalk";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation PG7V = ResourceLocation.parse("wflib:rpg7/heat");
    private static final ResourceLocation PG7VR = ResourceLocation.parse("wflib:rpg7/tandem");
    private static final ResourceLocation R556 = ResourceLocation.parse("wflib:5.56x45/ball");
    private static final ResourceLocation R50_AP = ResourceLocation.parse("wflib:12.7x99/ap");

    /** Lane indices past {@code SmallArmsArmorGameTest}'s own. */
    private static final int LANE = 20;

    /** 120 mm + air + 120 mm: the gap costs a shaped charge 1 / (1 + 0.6 x 2) of what the first layer left. */
    private static final String SPACED = "{\"facing\":\"side\",\"layers\":[{\"mm\":120,\"mat\":\"rha\"},{\"gap\":2.0},"
            + "{\"mm\":120,\"mat\":\"rha\"}]}";
    private static final String SOLID = "{\"facing\":\"side\",\"layers\":[{\"mm\":120,\"mat\":\"rha\"},"
            + "{\"mm\":120,\"mat\":\"rha\"}]}";

    private static void declare(AbstractVehicle vehicle, String side) {
        HullProfile base = vehicle.armorProfile;
        HullProfile profile = new HullProfile();
        profile.front = base.front;
        profile.side = base.side;
        profile.rear = base.rear;
        profile.top = base.top;
        profile.bottom = base.bottom;
        profile.ce = base.ce;
        profile.wearFloor = base.wearFloor;
        profile.parts = base.parts;
        profile.zones = base.zones;
        profile.plates = new HashMap<>(base.plates);
        profile.plates.put("side", new Gson().fromJson(side, PlateSpec.class));
        profile.validate();
        vehicle.armorProfile = profile;
    }

    /** Same plate, same rounds: the air gap alone turns a through shot into a stopped one; the tandem precursor ignores it. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void aSkirtWithAnAirGapStopsTheRpgAndTheTandemPasses(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> declare(v.get(), SOLID))
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), PG7V, "side"))
                .thenIdle(6).thenExecute(() -> SmallArmsArmorGameTest.expect(helper, v.get(), "penetration", "overmatch"))
                .thenExecute(() -> declare(v.get(), SPACED))
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), PG7V, "side"))
                .thenIdle(6).thenExecute(() -> SmallArmsArmorGameTest.expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), PG7VR, "side"))
                .thenIdle(6).thenExecute(() -> SmallArmsArmorGameTest.expect(helper, v.get(), "overmatch", "penetration")));
    }

    /**
     * 12.7 AP (30 mm) through the Humvee's two 10 mm sides: leaves with 10 left and hurts the husk 3.2 m behind the
     * entry plane; a 5.56 ball (stopped at the first wall) leaves it untouched.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void aRoundThatExitsTheHullHurtsWhatIsBehindIt(GameTestHelper helper) {
        Husk[] victim = new Husk[1];
        Aim[] aim = new Aim[1];
        float[] hull = new float[1];
        SmallArmsArmorGameTest.onLane(helper, LANE + 1, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    aim[0] = SmallArmsArmorGameTest.aimPlate(helper, v.get(), "side");
                    Vec3 dir = aim[0].normal().reverse();
                    Vec3 at = aim[0].point().add(dir.scale(3.2));
                    ServerLevel level = helper.getLevel();
                    victim[0] = new Husk(EntityType.HUSK, level);
                    victim[0].moveTo(at.x, Math.max(at.y - 1.0, v.get().getY() + 0.2), at.z, 0.0F, 0.0F);
                    victim[0].setNoAi(true);
                    victim[0].setNoGravity(true);
                    victim[0].setPersistenceRequired();
                    level.addFreshEntity(victim[0]);
                })
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), R556, "side"))
                .thenIdle(4)
                .thenExecute(() -> {
                    helper.assertTrue(victim[0].getHealth() == victim[0].getMaxHealth(),
                            "a stopped 5.56 hurt the husk behind: " + victim[0].getHealth());
                    hull[0] = v.get().getHealth();
                })
                .thenExecute(() -> SmallArmsArmorGameTest.firePlate(helper, v.get(), R50_AP, "side"))
                .thenIdle(4)
                .thenExecute(() -> {
                    List<String> got = SmallArmsArmorGameTest.verdicts(v.get());
                    helper.assertTrue(!got.isEmpty() && !got.contains("ricochet") && !got.contains("absorbed"),
                            "12.7 AP readout " + got);
                    helper.assertTrue(v.get().getHealth() < hull[0], "the hull lost nothing: " + got);
                    helper.assertTrue(!victim[0].isAlive() || victim[0].getHealth() < victim[0].getMaxHealth(),
                            "the round did not come out of the hull to the husk behind it: " + got);
                    victim[0].discard();
                }));
    }

    /**
     * APHE strike (BR-365 figures: 85 mm, 140 mm pen, 0.16 kg filler) posted onto the M1A2 along the plate normal: the
     * 770 mm turret cheek answers STOP (a stopped {@code post_pen} round is lost); the 60 mm hull side answers THROUGH
     * with the burst 0.3 m behind the entry plate, inside the hull. No catalogue APHE is requested by this mod, so the
     * strike is posted by hand; wflib's own tests cover what the round does with the answer.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void anApheRoundBurstsInsideTheHullItDefeats(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 2, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    ProjectileStrikeEvent cheek = aphe(v.get(), SmallArmsArmorGameTest.aimPlate(helper, v.get(), "turret_cheek_a"));
                    helper.assertTrue(cheek.outcome() == ProjectileStrikeEvent.Outcome.STOP, "cheek answered " + cheek.outcome());
                    Aim aim = SmallArmsArmorGameTest.aimPlate(helper, v.get(), "hull_side");
                    ProjectileStrikeEvent side = aphe(v.get(), aim);
                    helper.assertTrue(side.outcome() == ProjectileStrikeEvent.Outcome.THROUGH, "hull side answered " + side.outcome());
                    Vec3 burst = side.detonation();
                    helper.assertTrue(burst != null, "no burst point named");
                    double depth = burst.subtract(aim.point()).dot(aim.normal().reverse());
                    helper.assertTrue(depth > 0.05 && depth < 0.6, "burst " + depth + " m behind the entry plate, want ~0.3: "
                            + burst + " entry " + aim.point());
                    helper.assertTrue(v.get().getBoundingBox().inflate(0.2).contains(burst), "burst outside the hull: " + burst);
                    Vec3 out = side.through();
                    helper.assertTrue(out != null && out.dot(aim.normal()) < 0, "trail leaves outward: " + out);
                }));
    }

    private static ProjectileStrikeEvent aphe(AbstractVehicle vehicle, Aim aim) {
        Vec3 velocity = aim.normal().reverse().scale(40.0);
        Threat threat = new Threat(ThreatKind.KINETIC, 85f, 140f, 1f, 70f, 1.3f, 1.0f, 0.16f);
        return NeoForge.EVENT_BUS.post(new ProjectileStrikeEvent(null, 7L, vehicle, aim.point(), velocity, threat, null)
                .withPenExponent(1.43f));
    }

    /** Strike cost, logged: a stopped 5.56 and a through 12.7 AP on the Humvee side, whole {@code hurt} and the resolver alone. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void strikeCostIsLogged(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 3, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HullProfile plated = v.get().armorProfile;
                    HullProfile coarse = new HullProfile();
                    coarse.front = coarse.side = coarse.rear = coarse.top = coarse.bottom = 10;
                    for (HullProfile profile : new HullProfile[]{coarse, plated}) {
                        v.get().armorProfile = profile;
                        for (String round : new String[]{"wflib:5.56x45/ball", "wflib:12.7x99/ap"}) {
                            KineticPreset preset = KineticPresets.get(ResourceLocation.parse(round));
                            VehicleLab.Shot shot = VehicleLab.aim(level, HUMVEE, v.get(), round, 90, 0, 100);
                            Vec3 velocity = shot.dir().scale(shot.speed());
                            Threat threat = Rounds.threat(preset, velocity);
                            float damage = Rounds.impactDamage(preset, velocity);
                            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                            long before = mx.getThreadAllocatedBytes(Thread.currentThread().getId());
                            double[] ns = VehicleLab.strikeNanos(level, shot, velocity, threat, damage, 4000);
                            long bytes = mx.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
                            CombinedAssault.LOGGER.info("[walk] {} {}: hurt {} us, resolve {} us per strike, {} B allocated per hurt+resolve",
                                    profile == coarse ? "coarse" : "plates", round, String.format("%.2f", ns[0] / 1000.0),
                                    String.format("%.2f", ns[1] / 1000.0), bytes / (5000 + 4000));
                            helper.assertTrue(ns[0] < 250_000, round + " hurt " + ns[0] + " ns");
                        }
                    }
                    v.get().armorProfile = plated;
                    int n = 20000;
                    Vec3 from = v.get().position().add(8, 1.0, 0.0);
                    Vec3 to = v.get().position().add(-8, 1.0, 0.0);
                    List<com.wf.wflib.armor.api.hull.HullMesh.Crossing> out = new java.util.ArrayList<>();
                    int count = 0;
                    List<com.wf.combinedassault.ballistics.hit.VehicleHits.Hit> modules = new java.util.ArrayList<>();
                    for (int i = 0; i < n / 4; i++) {
                        out.clear();
                        count = v.get().hits().surfaces(from, to, out);
                    }
                    long t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        out.clear();
                        count = v.get().hits().surfaces(from, to, out);
                    }
                    double us = (System.nanoTime() - t0) / (double) n / 1000.0;
                    com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                    long b0 = mx.getThreadAllocatedBytes(Thread.currentThread().getId());
                    for (int i = 0; i < 1000; i++) {
                        out.clear();
                        v.get().hits().surfaces(from, to, out);
                    }
                    long perQuery = (mx.getThreadAllocatedBytes(Thread.currentThread().getId()) - b0) / 1000;
                    CombinedAssault.LOGGER.info("[walk] surfaces across the humvee: {} crossings, {} us, {} B per query", count,
                            String.format("%.2f", us), perQuery);
                    for (int i = 0; i < n / 4; i++) {
                        v.get().hits().surface(from, to);
                        out.clear();
                        v.get().hits().modules(from, to, modules);
                    }
                    t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        v.get().hits().surface(from, to);
                    }
                    double surface = (System.nanoTime() - t0) / (double) n / 1000.0;
                    t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        modules.clear();
                        v.get().hits().modules(from, to, modules);
                    }
                    double module = (System.nanoTime() - t0) / (double) n / 1000.0;
                    CombinedAssault.LOGGER.info("[walk] first-hit surface() {} us, modules() {} us ({} found)",
                            String.format("%.2f", surface), String.format("%.2f", module), modules.size());
                    helper.assertTrue(count >= 2, "a line through the hull met " + count + " surfaces");
                }));
    }
}
