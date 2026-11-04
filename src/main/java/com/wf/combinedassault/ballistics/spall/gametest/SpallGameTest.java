package com.wf.combinedassault.ballistics.spall.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.labs.VehicleLab;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.event.ProjectileStrikeEvent;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Layer 3: crew behind a defeated plate takes debris; a stopped round, nothing; a wreck's blast and fire reach crew. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SpallGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    private static final int LANE_Z = -1700, LANE_PITCH = -128, FLOOR_X = 5, FLOOR_Z = 8;
    private static final List<ArmorStand> SHOOTERS = new ArrayList<>();

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void overmatchWoundsSeatedOccupant(GameTestHelper helper) {
        shoot(helper, 0, HUMVEE, "wflib:12.7x99/ball", true, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void stoppedRoundLeavesOccupantUntouched(GameTestHelper helper) {
        shoot(helper, 1, M1A2, "wflib:5.56x45/ball", true, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void heatJetWoundsOccupantBehindPlate(GameTestHelper helper) {
        shoot(helper, 2, HUMVEE, "wflib:rpg7/heat", true, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void wreckBlastAndFireReachCrew(GameTestHelper helper) {
        TestLanes.run(helper, LANE_Z + 3 * LANE_PITCH, FLOOR_X, FLOOR_Z, HUMVEE, (seq, v) -> {
            Zombie[] crew = new Zombie[1];
            float[] before = new float[1];
            return seq.thenExecute(() -> {
                        crew[0] = seat(helper, v.get(), v.get().position().add(0, 1.2, 0));
                        crew[0].hurt(helper.getLevel().damageSources().generic(), 5);
                        helper.assertTrue(crew[0].getHealth() == crew[0].getMaxHealth(),
                                "occupant of an intact vehicle took " + (crew[0].getMaxHealth() - crew[0].getHealth()));
                        before[0] = crew[0].getHealth();
                        v.get().hurt(com.wf.combinedassault.vehicle.VehicleDamageTypes.Sources.explosion(helper.getLevel().registryAccess(),
                                null, null, v.get().position().add(0, 1, 0)), v.get().getMaxHealth() * 10);
                    })
                    .thenIdle(40)
                    .thenExecute(() -> {
                        helper.assertTrue(v.get().isDestroyed(), "vehicle not destroyed");
                        helper.assertTrue(crew[0].getHealth() < before[0] || !crew[0].isAlive(),
                                "wreck blast and fire left the occupant at " + crew[0].getHealth() + " of " + before[0]);
                        crew[0].discard();
                    });
        }, at -> SHOOTERS.removeIf(a -> {
            if (a.level() == helper.getLevel() && a.blockPosition().closerThan(at, 64)) {
                a.discard();
                return true;
            }
            return false;
        }));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void fragmentsPerPenetrationAreBounded(GameTestHelper helper) {
        TestLanes.run(helper, LANE_Z + 4 * LANE_PITCH, FLOOR_X, FLOOR_Z, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    KineticPreset preset = KineticPresets.get(ResourceLocation.parse("wflib:12.7x99/ball"));
                    VehicleLab.Shot shot = VehicleLab.aim(level, HUMVEE, v.get(), "wflib:12.7x99/ball", 90, 0, 100);
                    Vec3 velocity = shot.dir().scale(shot.speed());
                    Threat threat = Rounds.threat(preset, velocity);
                    Zombie crew = seat(helper, v.get(), shot.ray().hit().point().add(shot.dir().scale(1.2)));
                    double ns = VehicleLab.spallNanos(level, shot, velocity, threat, Rounds.impactDamage(preset, velocity), 2000);
                    crew.discard();
                    helper.assertTrue(ns < 2.0e6, "spall " + ns + " ns per penetration");
                    CombinedAssault.LOGGER.info("[spall] {} us per penetration, 1 occupant, cap {}", ns / 1000.0,
                            com.wf.combinedassault.ballistics.spall.Spall.MAX_FRAGMENTS);
                }), at -> { });
    }

    private static Zombie seat(GameTestHelper helper, AbstractVehicle vehicle, Vec3 at) {
        Zombie z = EntityType.ZOMBIE.create(helper.getLevel());
        z.setNoAi(true);
        z.setPos(at);
        helper.getLevel().addFreshEntity(z);
        helper.assertTrue(z.startRiding(vehicle, true), "occupant could not board");
        z.setPos(at);
        return z;
    }

    private static void shoot(GameTestHelper helper, int lane, ResourceLocation id, String round, boolean seated, boolean wounded) {
        List<ProjectileStrikeEvent> strikes = new ArrayList<>();
        NeoForge.EVENT_BUS.addListener(ProjectileStrikeEvent.class, strikes::add);
        Zombie[] crew = new Zombie[1];
        TestLanes.run(helper, LANE_Z + lane * LANE_PITCH, FLOOR_X, FLOOR_Z, id, (seq, v) -> seq
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    AbstractVehicle vehicle = v.get();
                    vehicle.setNoGravity(true);
                    VehicleLab.Shot shot = VehicleLab.aim(level, id, vehicle, round, 90, 0, 100);
                    helper.assertTrue(shot.ray().hit() != null, "no surface on the shot line");
                    crew[0] = seat(helper, vehicle, shot.ray().hit().point().add(shot.dir().scale(1.2)));
                    KineticPreset preset = KineticPresets.get(ResourceLocation.parse(round));
                    ArmorStand shooter = new ArmorStand(level, shot.start().x, shot.start().y, shot.start().z);
                    shooter.setInvisible(true);
                    shooter.setNoGravity(true);
                    level.addFreshEntity(shooter);
                    SHOOTERS.add(shooter);
                    if (preset.motorAccel() > 0) {
                        com.wf.wflib.round.api.RocketEntity.fire(level, preset, shot.start(), shot.dir(), 0, Vec3.ZERO, shooter, null, null);
                    } else {
                        Rounds.launch(level, preset, shot.start(), shot.dir().scale(shot.speed()), shooter, null, 1.0f, 0, 0);
                    }
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    float lost = crew[0].getMaxHealth() - crew[0].getHealth();
                    crew[0].discard();
                    if (wounded) {
                        helper.assertTrue(lost > 0, "occupant behind a defeated plate took nothing");
                    } else {
                        helper.assertTrue(lost == 0, "occupant behind a stopped round lost " + lost);
                    }
                }), at -> SHOOTERS.removeIf(a -> {
                    if (a.level() == helper.getLevel() && a.blockPosition().closerThan(at, 64)) {
                        a.discard();
                        return true;
                    }
                    return false;
                }));
    }

}
