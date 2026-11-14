package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.TriangleBvh;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.wflib.armor.api.hull.HullFace;
import com.wf.wflib.armor.api.hull.HullWear;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.RocketEntity;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * MineAssault fire path ({@code ServerFire.launch}: WFLib catalogue preset, {@code Rounds.launch} / {@code RocketEntity.fire})
 * into CA hull armour: verdict = {@link HitVehicleEvent} readout key. Speed factor f => armour pen x f^penExponent (SMALL_ARMS 2.0).
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SmallArmsArmorGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation M113 = CombinedAssault.modLocation("m113");
    private static final ResourceLocation T90 = CombinedAssault.modLocation("t90");
    private static final ResourceLocation R556 = ResourceLocation.parse("wflib:5.56x45/ball");
    private static final ResourceLocation R762 = ResourceLocation.parse("wflib:7.62x51/ball");
    private static final ResourceLocation R50 = ResourceLocation.parse("wflib:12.7x99/ball");
    private static final ResourceLocation PG7V = ResourceLocation.parse("wflib:rpg7/heat");
    private static final ResourceLocation DART_120 = ResourceLocation.parse("wflib:120mm/apfsds");
    private static final ResourceLocation AP_30 = ResourceLocation.parse("wflib:30x173/ap");
    private static final String RICOCHET_BATCH = "armorricochet";
    private static final String PLATE_BATCH = "platesets";
    /** .50 ball full payload at the muzzle (overmatch readout, Humvee). */
    private static final float FIFTY_FULL = 38.76f;
    /** Grazed rounds leave along z, ~45 blocks/tick: pitch > 2 ticks' reach. */
    private static final int LANE_Z = -700, LANE_PITCH = -128;
    private static final int FLOOR_X = 5, FLOOR_Z = 8;
    /** Ricochet exit scatter ({@code AddonStrikes.glance}) is drawn from the vehicle's random. */
    private static final long RICOCHET_SEED = 1L;
    private static final List<HitVehicleEvent> HITS = new ArrayList<>();
    private static boolean listening;
    private static final List<ArmorStand> SHOOTERS = new ArrayList<>();
    /** Vehicle id -> its last {@link #fire}. */
    private static final Map<Integer, Shot> LAST = new HashMap<>();

    /** Humvee side 10 mm: 5.56 (5) stopped, 7.62 (8) partly through, .50 (20) overmatch. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void calibreDecidesAgainstHumveeSide(GameTestHelper helper) {
        onLane(helper, 0, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> fire(helper, v.get(), R556, 1, 0, HullFace.SIDE, 1.0))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> fire(helper, v.get(), R762, 1, 0, HullFace.SIDE, 1.0))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "partial", "penetration"))
                .thenExecute(() -> fire(helper, v.get(), R50, 1, 0, HullFace.SIDE, 1.0))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "overmatch")));
    }

    /** Pen x |v|^2 / v0^2: .50 ball 20 mm at f = 1 / 0.75 / 0.65 / 0.5 vs 10 mm; damage falls with it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 240)
    public static void penetrationFallsWithImpactSpeed(GameTestHelper helper) {
        float[] damage = new float[4];
        String[] want = {"overmatch", "penetration", "partial", "absorbed"};
        double[] f = {1, 0.75, 0.65, 0.5};
        onLane(helper, 1, HUMVEE, (seq, v) -> {
            for (int k = 0; k < 4; k++) {
                int i = k;
                seq.thenExecute(() -> fire(helper, v.get(), R50, f[i], 0, HullFace.SIDE, 1.0))
                        .thenIdle(3)
                        .thenExecute(() -> damage[i] = expect(helper, v.get(), want[i]));
            }
            return seq.thenExecute(() -> {
                for (int k = 1; k < 4; k++) {
                    helper.assertTrue(damage[k] < damage[k - 1], String.format(Locale.ROOT,
                            "damage not falling with speed: %s", java.util.Arrays.toString(damage)));
                }
            });
        });
    }

    /** 940 mm turret front: .50 stopped, hull damage < 1% of its full payload (spall x ratio 0.03). */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void heavyMachineGunDoesNothingToTankFront(GameTestHelper helper) {
        onLane(helper, 2, M1A2, (seq, v) -> seq
                .thenExecute(() -> fire(helper, v.get(), R50, 1, 0, HullFace.FRONT, 2.2))
                .thenIdle(3)
                .thenExecute(() -> {
                    float lost = expect(helper, v.get(), "absorbed");
                    helper.assertTrue(lost < 0.01f * FIFTY_FULL, "MBT front lost " + lost + " to a .50 ball");
                }));
    }

    /** Incidence 80 deg > AP-class ricochet 70 (small-arms): shooter told, no damage, round flies on off the face; 60 deg: stopped. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void grazingRiflefireRicochets(GameTestHelper helper) {
        long[] key = new long[1];
        Vec3[] normal = new Vec3[1];
        float[] health = new float[1];
        onLane(helper, 3, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    health[0] = v.get().getHealth();
                    v.get().getRandom().setSeed(RICOCHET_SEED);
                    Shot s = fire(helper, v.get(), R556, 1, 80, HullFace.SIDE, 1.0);
                    key[0] = s.key;
                    normal[0] = s.normal;
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    helper.assertTrue(verdicts(v.get()).equals(List.of("ricochet")),
                            "graze readout " + verdicts(v.get()) + ", want [ricochet]");
                    helper.assertTrue(v.get().getHealth() == health[0], "graze cost " + (health[0] - v.get().getHealth()));
                    helper.assertTrue(Rounds.ricochets(level, key[0]) == 1,
                            "ricochets " + Rounds.ricochets(level, key[0]) + " (-1 = round gone)");
                    Vec3 out = Rounds.velocity(level, key[0]);
                    helper.assertTrue(out != null && out.dot(normal[0]) > 0, "deflected into the hull: " + out);
                })
                .thenExecute(() -> fire(helper, v.get(), R556, 1, 60, HullFace.SIDE, 1.0))
                .thenIdle(3)
                .thenExecute(() -> expect(helper, v.get(), "absorbed")));
    }

    /** PG-7V 260 mm (shaped charge: no speed falloff): M1A2 side 130 through, front 620 stopped. */
    @GameTest(template = TEMPLATE, timeoutTicks = 240)
    public static void rpgDefeatsTankSideNotFront(GameTestHelper helper) {
        onLane(helper, 4, M1A2, (seq, v) -> seq
                .thenExecute(() -> fire(helper, v.get(), PG7V, 1, 0, HullFace.SIDE, 1.2))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "overmatch"))
                .thenExecute(() -> fire(helper, v.get(), PG7V, 1, 0, HullFace.FRONT, 2.2))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "absorbed")));
    }

    /** M1A2 hull side 60 mm at 79 deg: APFSDS ricochet 82 => no skate, perforates (LOS 314 mm vs 800 mm pen). */
    @GameTest(template = TEMPLATE, batch = RICOCHET_BATCH, timeoutTicks = 200)
    public static void dartPerforatesSlopedPlate(GameTestHelper helper) {
        float[] health = new float[1];
        onLane(helper, 5, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    fire(helper, v.get(), DART_120, 1, 79, HullFace.SIDE, 1.2);
                    health[0] = v.get().getHealth();
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    List<String> got = verdicts(v.get());
                    helper.assertTrue(!got.isEmpty() && !got.contains("ricochet"), "dart readout " + got + ", want a non-ricochet verdict");
                    helper.assertTrue(v.get().getHealth() < health[0], "dart at 79 deg did no damage: " + got);
                }));
    }

    /** M1A2 hull side 60 mm, 30 mm AP (T/D 0.5 < 1): 72 deg > 70 skates unhurt; 60 deg does not. */
    @GameTest(template = TEMPLATE, batch = RICOCHET_BATCH, timeoutTicks = 240)
    public static void apSkatesAtSeventyTwoDegrees(GameTestHelper helper) {
        float[] health = new float[1];
        onLane(helper, 6, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    health[0] = v.get().getHealth();
                    v.get().getRandom().setSeed(RICOCHET_SEED);
                    fire(helper, v.get(), AP_30, 1, 72, HullFace.SIDE, 1.2);
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    helper.assertTrue(verdicts(v.get()).equals(List.of("ricochet")),
                            "AP 72 deg readout " + verdicts(v.get()) + ", want [ricochet]");
                    helper.assertTrue(v.get().getHealth() == health[0], "skate cost " + (health[0] - v.get().getHealth()));
                })
                .thenExecute(() -> fire(helper, v.get(), AP_30, 1, 60, HullFace.SIDE, 1.2))
                .thenIdle(4)
                .thenExecute(() -> helper.assertTrue(!verdicts(v.get()).contains("ricochet"),
                        "AP 60 deg skated: " + verdicts(v.get()))));
    }

    /** Humvee `side` plate 10 mm rha: 5.56 stopped, 7.62 partly through. */
    @GameTest(template = TEMPLATE, batch = PLATE_BATCH, timeoutTicks = 240)
    public static void humveeSidePlateStopsFiveFiveSixNotSevenSixTwo(GameTestHelper helper) {
        onLane(helper, 7, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> firePlate(helper, v.get(), R556, "side"))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> firePlate(helper, v.get(), R762, "side"))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "partial", "penetration")));
    }

    /** M113 `side` plate 38 mm aluminium (13.3 mm RHAe): 5.56 stopped, .50 through. */
    @GameTest(template = TEMPLATE, batch = PLATE_BATCH, timeoutTicks = 240)
    public static void m113SidePlateStopsFiveFiveSixNotFifty(GameTestHelper helper) {
        onLane(helper, 8, M113, (seq, v) -> seq
                .thenExecute(() -> firePlate(helper, v.get(), R556, "side"))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> firePlate(helper, v.get(), R50, "side"))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "penetration", "overmatch")));
    }

    /** M1A2 `hull_side` 60 mm: .50 stopped, 120 mm APFSDS through; `turret_cheek_a` stops the .50. */
    @GameTest(template = TEMPLATE, batch = PLATE_BATCH, timeoutTicks = 240)
    public static void m1a2HullSidePlateStopsFiftyNotDart(GameTestHelper helper) {
        onLane(helper, 9, M1A2, (seq, v) -> seq
                .thenExecute(() -> firePlate(helper, v.get(), R50, "hull_side"))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> firePlate(helper, v.get(), DART_120, "hull_side"))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "penetration", "overmatch"))
                .thenExecute(() -> firePlate(helper, v.get(), R50, "turret_cheek_a"))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "absorbed")));
    }

    /** T-90 `skirt_front` CE 400 stops PG-7V (260), `hull_side` CE 80 does not. */
    @GameTest(template = TEMPLATE, batch = PLATE_BATCH, timeoutTicks = 240)
    public static void t90SkirtPlateStopsRpgHullSideDoesNot(GameTestHelper helper) {
        onLane(helper, 10, T90, (seq, v) -> seq
                .thenExecute(() -> firePlate(helper, v.get(), PG7V, "skirt_front"))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> firePlate(helper, v.get(), PG7V, "hull_side"))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "overmatch", "penetration")));
    }

    record Shot(long key, Vec3 point, Vec3 normal, String desc) {
    }


    /**
     * Probe the hull face at {@code height} along local {@code face}, then launch {@code round} from 3 blocks off at
     * {@code incidence} deg from its normal (tilted along the face, horizontally). Health and armour wear restored
     * first: every shot meets fresh plate.
     */
    private static Shot fire(GameTestHelper helper, AbstractVehicle vehicle, ResourceLocation round, double speedFactor,
                             double incidence, HullFace face, double height) {
        fresh(vehicle);
        Quaternionf rot = new Quaternionf(vehicle.rotYXZ());
        Vector3f out = rot.transform(face == HullFace.FRONT ? new Vector3f(0, 0, 1) : new Vector3f(1, 0, 0));
        Vec3 centre = vehicle.position().add(0, height, 0);
        Vec3 probe = centre.add(out.x * 8, out.y * 8, out.z * 8);
        VehicleHits.Hit hit = vehicle.hits().surface(probe, centre);
        helper.assertTrue(hit != null, "no hull along " + face + " at height " + height);
        Vec3 n = new Vec3(hit.normal());
        HullFace struck = HullFace.of(new Quaternionf(rot).conjugate().transform(new Vector3f(hit.normal())));
        helper.assertTrue(struck == face, "probe met " + struck + " normal " + n + ", not " + face);
        return launch(helper, vehicle, round, speedFactor, incidence, hit.point(), n);
    }

    static void fresh(AbstractVehicle vehicle) {
        vehicle.setHealth(vehicle.getMaxHealth());
        for (HullWear.Face f : HullWear.Face.VALUES) {
            vehicle.hullWear().repair(f, 1);
        }
        HITS.removeIf(h -> h.entityId == vehicle.getId());
    }

    /** Launch from 3 blocks off {@code point}, {@code incidence} deg from {@code n}, tilted horizontally. */
    private static Shot launch(GameTestHelper helper, AbstractVehicle vehicle, ResourceLocation round, double speedFactor,
                               double incidence, Vec3 point, Vec3 n) {
        ServerLevel level = helper.getLevel();
        Vec3 tangent = n.cross(new Vec3(0, 1, 0)).normalize();
        double a = Math.toRadians(incidence);
        Vec3 dir = n.scale(-Math.cos(a)).add(tangent.scale(Math.sin(a))).normalize();
        Vec3 from = point.subtract(dir.scale(3));
        KineticPreset preset = KineticPresets.get(round);
        // Rounds resolve their shooter by id from the level: a FakePlayer reads as null (no readout).
        // Last shot's stand sits on this launch point: discard (its round has landed).
        SHOOTERS.removeIf(stand -> {
            if (stand.distanceToSqr(vehicle) < 32 * 32) {
                stand.discard();
                return true;
            }
            return false;
        });
        ArmorStand shooter = new ArmorStand(level, from.x, from.y, from.z);
        shooter.setInvisible(true);
        shooter.setNoGravity(true);
        level.addFreshEntity(shooter);
        SHOOTERS.add(shooter);
        long key;
        if (preset.motorAccel() > 0) {
            key = RocketEntity.fire(level, preset, from, dir, 0, Vec3.ZERO, shooter, null, null).getId();
        } else {
            key = Rounds.launch(level, preset, from, dir.scale(preset.muzzleSpeed() * speedFactor), shooter, null,
                    1.0f, 0, 0);
        }
        Shot shot = new Shot(key, point, n, String.format(Locale.ROOT, "%s from %s dir %s aim %s", round, from, dir, point));
        LAST.put(vehicle.getId(), shot);
        return shot;
    }

    /** Largest triangle bound to {@code plate} that a ray along its outward normal reaches: fire at it, incidence 0 to the mesh. */
    static Shot firePlate(GameTestHelper helper, AbstractVehicle vehicle, ResourceLocation round, String plate) {
        Aim aim = aimPlate(helper, vehicle, plate);
        return launch(helper, vehicle, round, 1.0, 0, aim.point(), aim.normal());
    }

    /** Where {@link #firePlate} aims: point on the face, outward normal. */
    record Aim(Vec3 point, Vec3 normal) {
    }

    static Aim aimPlate(GameTestHelper helper, AbstractVehicle vehicle, String plate) {
        fresh(vehicle);
        VehicleHits hits = vehicle.hits();
        Matrix4f pose = new Matrix4f();
        Vector3f n = new Vector3f();
        float[] normal = new float[3];
        double bestArea = 0;
        Vec3 bestPoint = null, bestNormal = null;
        for (int i = 0; i < hits.model().nodes().size(); i++) {
            HitModel.Node node = hits.model().nodes().get(i);
            TriangleBvh bvh = node.mesh().mesh();
            if (bvh == null || node.mesh().module() || !hits.live(i)) {
                continue;
            }
            hits.pose(i, vehicle.position(), pose);
            float[] t = bvh.triangles();
            for (int tri = 0; tri < bvh.triangleCount(); tri++) {
                if (!plate.equals(hits.model().mesh().plateId(bvh.plate(tri)))) {
                    continue;
                }
                int a = tri * 9;
                Vector3f p0 = pose.transformPosition(new Vector3f(t[a], t[a + 1], t[a + 2]));
                Vector3f p1 = pose.transformPosition(new Vector3f(t[a + 3], t[a + 4], t[a + 5]));
                Vector3f p2 = pose.transformPosition(new Vector3f(t[a + 6], t[a + 7], t[a + 8]));
                double area = p1.sub(p0, new Vector3f()).cross(p2.sub(p0, new Vector3f())).length() / 2;
                if (area <= bestArea) {
                    continue;
                }
                Vector3f c = new Vector3f(p0).add(p1).add(p2).div(3);
                bvh.normal(tri, normal);
                pose.transformDirection(n.set(normal[0], normal[1], normal[2])).normalize();
                Vec3 centre = vehicle.position().add(c.x, c.y, c.z);
                for (int side = -1; side <= 1; side += 2) {
                    Vec3 dir = new Vec3(n.x, n.y, n.z).scale(-side);
                    VehicleHits.Hit hit = hits.surface(centre.subtract(dir.scale(3)), centre.add(dir.scale(0.05)));
                    if (hit != null && plate.equals(hit.plate()) && hit.point().distanceTo(centre) < 0.03) {
                        bestArea = area;
                        bestPoint = hit.point();
                        bestNormal = new Vec3(hit.normal());
                        break;
                    }
                }
            }
        }
        helper.assertTrue(bestPoint != null, "no face of plate '" + plate + "' reachable from outside");
        return new Aim(bestPoint, bestNormal);
    }

    /** One verdict of {@code want} on this vehicle since the last {@link #fire}; returns hull health lost. */
    static float expect(GameTestHelper helper, AbstractVehicle vehicle, String... want) {
        List<String> got = verdicts(vehicle);
        if (got.stream().noneMatch(v -> List.of(want).contains(v))) {
            Shot shot = LAST.get(vehicle.getId());
            helper.fail("want " + String.join("|", want) + ", readout " + got + "; lost "
                    + (vehicle.getMaxHealth() - vehicle.getHealth()) + "; " + shot.desc + "; round now "
                    + Rounds.position(helper.getLevel(), shot.key) + " ricochets "
                    + Rounds.ricochets(helper.getLevel(), shot.key) + "; vehicle at " + vehicle.position());
        }
        return vehicle.getMaxHealth() - vehicle.getHealth();
    }

    static List<String> verdicts(AbstractVehicle vehicle) {
        return HITS.stream().filter(h -> h.entityId == vehicle.getId())
                .map(h -> h.message.getContents() instanceof TranslatableContents t ? t.getKey() : h.message.getString())
                .map(k -> k.substring(k.lastIndexOf('.') + 1)).toList();
    }

    static void onLane(GameTestHelper helper, int lane, ResourceLocation id, TestLanes.Body body) {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(HitVehicleEvent.class, HITS::add);
        }
        TestLanes.run(helper, LANE_Z + lane * LANE_PITCH, FLOOR_X, FLOOR_Z, id, body,
                at -> SHOOTERS.removeIf(a -> {
                    if (a.level() == helper.getLevel() && a.blockPosition().closerThan(at, 32)) {
                        a.discard();
                        return true;
                    }
                    return false;
                }));
    }
}
