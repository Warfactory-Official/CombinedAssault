package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.api.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.ArmorFace;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RocketEntity;
import com.wf.wflib.round.Rounds;
import net.minecraft.core.BlockPos;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MineAssault fire path ({@code ServerFire.launch}: WFLib catalogue preset, {@code Rounds.launch} / {@code RocketEntity.fire})
 * into CA hull armour: verdict = {@link HitVehicleEvent} readout key. Speed factor f => armour pen x f^2 (range).
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SmallArmsArmorGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation R556 = ResourceLocation.parse("wflib:5.56x45/ball");
    private static final ResourceLocation R762 = ResourceLocation.parse("wflib:7.62x51/ball");
    private static final ResourceLocation R50 = ResourceLocation.parse("wflib:12.7x99/ball");
    private static final ResourceLocation PG7V = ResourceLocation.parse("wflib:rpg7/heat");
    /** .50 ball full payload at the muzzle (overmatch readout, Humvee). */
    private static final float FIFTY_FULL = 38.76f;
    /** Grazed rounds leave along z, ~45 blocks/tick: pitch > 2 ticks' reach. */
    private static final int LANE_Z = -700, LANE_PITCH = -128;
    private static final int FLOOR_X = 5, FLOOR_Z = 8;
    private static final List<HitVehicleEvent> HITS = new ArrayList<>();
    private static final List<ArmorStand> SHOOTERS = new ArrayList<>();
    /** Vehicle id -> its last {@link #fire}. */
    private static final Map<Integer, Shot> LAST = new HashMap<>();

    static {
        NeoForge.EVENT_BUS.addListener(HitVehicleEvent.class, HITS::add);
    }

    /** Humvee side 8 mm: 5.56 (5) stopped, 7.62 (8) through, .50 (20) overmatch. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void calibreDecidesAgainstHumveeSide(GameTestHelper helper) {
        onLane(helper, 0, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> fire(helper, v.get(), R556, 1, 0, ArmorFace.SIDE, 1.0))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "absorbed"))
                .thenExecute(() -> fire(helper, v.get(), R762, 1, 0, ArmorFace.SIDE, 1.0))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "partial", "penetration"))
                .thenExecute(() -> fire(helper, v.get(), R50, 1, 0, ArmorFace.SIDE, 1.0))
                .thenIdle(3).thenExecute(() -> expect(helper, v.get(), "overmatch")));
    }

    /** Pen x |v|^2 / v0^2: .50 ball 20 mm at f = 1 / 0.75 / 0.6 / 0.45 vs 8 mm; damage falls with it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 240)
    public static void penetrationFallsWithImpactSpeed(GameTestHelper helper) {
        float[] damage = new float[4];
        String[] want = {"overmatch", "penetration", "partial", "absorbed"};
        double[] f = {1, 0.75, 0.6, 0.45};
        onLane(helper, 1, HUMVEE, (seq, v) -> {
            for (int k = 0; k < 4; k++) {
                int i = k;
                seq.thenExecute(() -> fire(helper, v.get(), R50, f[i], 0, ArmorFace.SIDE, 1.0))
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

    /** 620 mm front: .50 stopped, hull damage < 1% of its full payload (spall x ratio 0.03). */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void heavyMachineGunDoesNothingToTankFront(GameTestHelper helper) {
        onLane(helper, 2, M1A2, (seq, v) -> seq
                .thenExecute(() -> fire(helper, v.get(), R50, 1, 0, ArmorFace.FRONT, 2.2))
                .thenIdle(3)
                .thenExecute(() -> {
                    float lost = expect(helper, v.get(), "absorbed");
                    helper.assertTrue(lost < 0.01f * FIFTY_FULL, "MBT front lost " + lost + " to a .50 ball");
                }));
    }

    /** Incidence 80 deg > ricochet_angle 70: shooter told, no damage, round flies on off the face; 60 deg: stopped. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void grazingRiflefireRicochets(GameTestHelper helper) {
        long[] key = new long[1];
        Vec3[] normal = new Vec3[1];
        float[] health = new float[1];
        onLane(helper, 3, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    health[0] = v.get().getHealth();
                    Shot s = fire(helper, v.get(), R556, 1, 80, ArmorFace.SIDE, 1.0);
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
                .thenExecute(() -> fire(helper, v.get(), R556, 1, 60, ArmorFace.SIDE, 1.0))
                .thenIdle(3)
                .thenExecute(() -> expect(helper, v.get(), "absorbed")));
    }

    /** PG-7V 260 mm (shaped charge: no speed falloff): M1A2 side 130 through, front 620 stopped. */
    @GameTest(template = TEMPLATE, timeoutTicks = 240)
    public static void rpgDefeatsTankSideNotFront(GameTestHelper helper) {
        onLane(helper, 4, M1A2, (seq, v) -> seq
                .thenExecute(() -> fire(helper, v.get(), PG7V, 1, 0, ArmorFace.SIDE, 1.2))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "overmatch"))
                .thenExecute(() -> fire(helper, v.get(), PG7V, 1, 0, ArmorFace.FRONT, 2.2))
                .thenIdle(6).thenExecute(() -> expect(helper, v.get(), "absorbed")));
    }

    private record Shot(long key, Vec3 normal, String desc) {
    }


    /**
     * Probe the hull face at {@code height} along local {@code face}, then launch {@code round} from 3 blocks off at
     * {@code incidence} deg from its normal (tilted along the face, horizontally). Health restored first: wear stays 1.
     */
    private static Shot fire(GameTestHelper helper, AbstractVehicle vehicle, ResourceLocation round, double speedFactor,
                             double incidence, ArmorFace face, double height) {
        ServerLevel level = helper.getLevel();
        vehicle.setHealth(vehicle.getMaxHealth());
        HITS.removeIf(h -> h.entityId == vehicle.getId());
        Quaternionf rot = new Quaternionf(vehicle.rotYXZ());
        Vector3f out = rot.transform(face == ArmorFace.FRONT ? new Vector3f(0, 0, 1) : new Vector3f(1, 0, 0));
        Vec3 centre = vehicle.position().add(0, height, 0);
        Vec3 probe = centre.add(out.x * 8, out.y * 8, out.z * 8);
        VehicleHits.Hit hit = vehicle.hits().surface(probe, centre);
        helper.assertTrue(hit != null, "no hull along " + face + " at height " + height);
        Vec3 n = new Vec3(hit.normal());
        ArmorFace struck = ArmorFace.of(new Quaternionf(rot).conjugate().transform(new Vector3f(hit.normal())));
        helper.assertTrue(struck == face, "probe met " + struck + " normal " + n + ", not " + face);
        Vec3 tangent = n.cross(new Vec3(0, 1, 0)).normalize();
        double a = Math.toRadians(incidence);
        Vec3 dir = n.scale(-Math.cos(a)).add(tangent.scale(Math.sin(a))).normalize();
        Vec3 from = hit.point().subtract(dir.scale(3));
        KineticPreset preset = KineticPresetRegistry.get(round);
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
        Shot shot = new Shot(key, n, String.format(Locale.ROOT, "%s from %s dir %s aim %s", round, from, dir, hit.point()));
        LAST.put(vehicle.getId(), shot);
        return shot;
    }

    /** One verdict of {@code want} on this vehicle since the last {@link #fire}; returns hull health lost. */
    private static float expect(GameTestHelper helper, AbstractVehicle vehicle, String... want) {
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

    private static List<String> verdicts(AbstractVehicle vehicle) {
        return HITS.stream().filter(h -> h.entityId == vehicle.getId())
                .map(h -> h.message.getContents() instanceof TranslatableContents t ? t.getKey() : h.message.getString())
                .map(k -> k.substring(k.lastIndexOf('.') + 1)).toList();
    }

    private static void onLane(GameTestHelper helper, int lane, ResourceLocation id, TestLanes.Body body) {
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
