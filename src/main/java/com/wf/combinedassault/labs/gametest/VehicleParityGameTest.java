package com.wf.combinedassault.labs.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.labs.VehicleLab;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.wflib.armor.api.hull.HullWear;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.event.ProjectileStrikeEvent;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live rounds into a settled vehicle equal what the lab computes for the same ray, threat and damage: verdict, hull
 * damage, struck point.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleParityGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    private static final int LANE_Z = -1100, LANE_PITCH = -128, FLOOR_X = 5, FLOOR_Z = 8;
    private static final List<ProjectileStrikeEvent> STRIKES = new ArrayList<>();
    private static final List<HitVehicleEvent> HITS = new ArrayList<>();
    private static final List<ArmorStand> SHOOTERS = new ArrayList<>();
    private static boolean listening;

    private record Case(ResourceLocation vehicle, String round, double azimuth, double elevation, double range) {
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void fiftyCalAgainstTankSide(GameTestHelper helper) {
        parity(helper, 0, new Case(M1A2, "wflib:12.7x99/ball", 90, 0, 100));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void heatAgainstTankSide(GameTestHelper helper) {
        parity(helper, 1, new Case(M1A2, "wflib:rpg7/heat", 90, 0, 100));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void rifleAgainstHumveeSide(GameTestHelper helper) {
        parity(helper, 2, new Case(HUMVEE, "wflib:7.62x51/ball", 90, 0, 100));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void grazingRoundRicochets(GameTestHelper helper) {
        parity(helper, 3, new Case(HUMVEE, "wflib:5.56x45/ball", 10, 0, 100));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void plungingFireOnTankRoof(GameTestHelper helper) {
        parity(helper, 4, new Case(M1A2, "wflib:12.7x99/ball", 30, 50, 300));
    }

    private static void parity(GameTestHelper helper, int lane, Case c) {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(ProjectileStrikeEvent.class, STRIKES::add);
            NeoForge.EVENT_BUS.addListener(HitVehicleEvent.class, HITS::add);
        }
        KineticPreset preset = KineticPresets.get(ResourceLocation.parse(c.round));
        VehicleLab.Shot[] shot = new VehicleLab.Shot[1];
        TestLanes.run(helper, LANE_Z + lane * LANE_PITCH, FLOOR_X, FLOOR_Z, c.vehicle, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v.get();
                    vehicle.setNoGravity(true);
                    shot[0] = VehicleLab.aim(helper.getLevel(), c.vehicle, vehicle, c.round, c.azimuth, c.elevation, c.range);
                    launch(helper, preset, shot[0]);
                })
                .thenIdle(8)
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v.get();
                    ProjectileStrikeEvent strike = STRIKES.stream().filter(s -> s.target() == vehicle).findFirst().orElse(null);
                    HitVehicleEvent hit = HITS.stream().filter(h -> h.entityId == vehicle.getId()).findFirst().orElse(null);
                    helper.assertTrue(strike != null && hit != null, "no strike/hit recorded: " + STRIKES.size() + "/" + HITS.size());
                    Threat live = strike.threat();
                    for (HullWear.Face f : HullWear.Face.VALUES) {
                        vehicle.hullWear().repair(f, 1);
                    }
                    VehicleLab.Expected want = VehicleLab.expect(helper.getLevel(), shot[0], strike.velocity(), live);
                    String got = hit.message.getContents() instanceof TranslatableContents t
                            ? t.getKey().substring(t.getKey().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "?";
                    helper.assertTrue(got.equals(want.verdict()), "verdict live " + got + " lab " + want.verdict() + " " + want);
                    double tolerance = Math.max(0.02 * want.hullDamage(), 0.01);
                    helper.assertTrue(Math.abs(hit.damage - want.hullDamage()) <= tolerance,
                            "hull damage live " + hit.damage + " lab " + want.hullDamage() + " " + want + " liveThreat " + live
                                    + " liveV " + strike.velocity() + " labDir " + shot[0].dir() + " liveHit " + hit.hitVector);
                    helper.assertTrue(hit.hitPosition.distanceTo(want.hitWorld()) < 0.05,
                            "hit point live " + hit.hitPosition + " lab " + want.hitWorld());
                    VehicleLab.Row row = VehicleLab.producerRow(helper.getLevel(), c.vehicle, c.round, c.range, c.azimuth, c.elevation);
                    helper.assertTrue(row != null && row.verdict().equals(got), "producer row " + row + " vs live " + got);
                    helper.assertTrue(row.local().distanceTo(want.hitWorld().subtract(vehicle.position())) < 0.05,
                            "producer hit " + row.local() + " live " + want.hitWorld().subtract(vehicle.position()));
                }), at -> SHOOTERS.removeIf(a -> {
                    if (a.level() == helper.getLevel() && a.blockPosition().closerThan(at, 64)) {
                        a.discard();
                        return true;
                    }
                    return false;
                }));
    }

    private static void launch(GameTestHelper helper, KineticPreset preset, VehicleLab.Shot shot) {
        ServerLevel level = helper.getLevel();
        ArmorStand shooter = new ArmorStand(level, shot.start().x, shot.start().y, shot.start().z);
        shooter.setInvisible(true);
        shooter.setNoGravity(true);
        level.addFreshEntity(shooter);
        SHOOTERS.add(shooter);
        if (preset.motorAccel() > 0) {
            RocketEntity.fire(level, preset, shot.start(), shot.dir(), 0, Vec3.ZERO, shooter, null, null);
        } else {
            Rounds.launch(level, preset, shot.start(), shot.dir().scale(shot.speed()), shooter, null, 1.0f, 0, 0);
        }
    }

}
