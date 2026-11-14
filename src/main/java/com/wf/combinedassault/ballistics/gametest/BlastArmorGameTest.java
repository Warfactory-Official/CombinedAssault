package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.BlastRays;
import com.wf.combinedassault.ballistics.VehicleBlastResolver;
import com.wf.combinedassault.ballistics.gametest.SmallArmsArmorGameTest.Aim;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.explosion.api.BlastPayload;
import com.wf.wflib.explosion.api.EntityProcessorCross;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.explosion.api.PlayerProcessorStandard;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Warhead payloads on real vehicles through the AEF ({@code BlastTarget}): HE per face, jet from the detonation point,
 * HESH, a burst inside the hull, a mine under the belly. Verdicts = {@code HitVehicleEvent} readout (jet) or what the
 * victim lost.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BlastArmorGameTest {

    private static final String TEMPLATE = "empty";
    private static final String BATCH = "armorblast";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation M113 = CombinedAssault.modLocation("m113");
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    /** Lane indices past {@code WalkGameTest}'s. */
    private static final int LANE = 30;
    /** 155 mm HE: 10.8 kg filler, blast size 8. */
    private static final BlastPayload SHELL = BlastPayload.blast(10.8f);
    private static final float SHELL_SIZE = 8.0f;
    private static final List<ArmorStand> STANDS = new ArrayList<>();

    private static ArmorStand stand(GameTestHelper helper, Vec3 at) {
        ArmorStand stand = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        helper.getLevel().addFreshEntity(stand);
        STANDS.add(stand);
        return stand;
    }

    private static void clean(GameTestHelper helper) {
        STANDS.removeIf(a -> {
            a.discard();
            return true;
        });
    }

    /** Charge {@code distance} m off the hull side at the height of the aim, through the AEF entity stage only. */
    private static void burst(GameTestHelper helper, AbstractVehicle vehicle, BlastPayload payload, float size, double distance,
                              double height) {
        SmallArmsArmorGameTest.fresh(vehicle);
        Vec3 centre = vehicle.position().add(0, height, 0);
        Vec3 out = new Vec3(new org.joml.Quaternionf(vehicle.rotYXZ()).transform(new org.joml.Vector3f(1, 0, 0)));
        VehicleHits.Hit hit = vehicle.hits().surface(centre.add(out.scale(8)), centre);
        helper.assertTrue(hit != null, "no hull along the side at height " + height);
        Vec3 at = hit.point().add(new Vec3(hit.normal()).scale(distance));
        new ExplosionAEF(helper.getLevel(), at.x, at.y, at.z, size, stand(helper, at.add(0, 5, 0)))
                .payload(payload).setEntityProcessor(new EntityProcessorCross()).setPlayerProcessor(new PlayerProcessorStandard())
                .explode();
    }

    private static float lost(AbstractVehicle vehicle) {
        return vehicle.getMaxHealth() - vehicle.getHealth();
    }

    /** Humvee side 10 mm: a 155 mm shell 3 m off punches it, the hull is hurt. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aShellNearMissPunchesTheHumveeSide(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    burst(helper, v.get(), SHELL, SHELL_SIZE, 3.0, 1.2);
                    CombinedAssault.LOGGER.info("[blast] humvee side, 155 mm at 3 m: hull lost {} of {}", lost(v.get()),
                            v.get().getMaxHealth());
                    helper.assertTrue(VehicleBlastResolver.hePen(10.8f, 3.0) > 10.0f,
                            "blast pen " + VehicleBlastResolver.hePen(10.8f, 3.0) + " mm does not beat the 10 mm side");
                    helper.assertTrue(lost(v.get()) > 0.05f * v.get().getMaxHealth(),
                            "a 155 mm shell 3 m off the Humvee side cost " + lost(v.get()));
                    clean(helper);
                }));
    }

    /** M1A2 hull side 60-80 mm: the same shell, same place, no penetration: only spall-grade damage. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aShellNearMissDoesNotPunchTheTankSide(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 1, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    burst(helper, v.get(), SHELL, SHELL_SIZE, 3.0, 1.6);
                    CombinedAssault.LOGGER.info("[blast] m1a2 side, 155 mm at 3 m: hull lost {} of {}", lost(v.get()),
                            v.get().getMaxHealth());
                    helper.assertTrue(lost(v.get()) < 0.02f * v.get().getMaxHealth(),
                            "a 155 mm shell 3 m off the M1A2 side cost " + lost(v.get()));
                    clean(helper);
                }));
    }

    /** M113 aluminium side: through at 3 m. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aShellNearMissPunchesTheCarrierSide(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 2, M113, (seq, v) -> seq
                .thenExecute(() -> {
                    burst(helper, v.get(), SHELL, SHELL_SIZE, 3.0, 1.4);
                    CombinedAssault.LOGGER.info("[blast] m113 side, 155 mm at 3 m: hull lost {} of {}", lost(v.get()),
                            v.get().getMaxHealth());
                    helper.assertTrue(lost(v.get()) > 0.02f * v.get().getMaxHealth(),
                            "a 155 mm shell 3 m off the M113 side cost " + lost(v.get()));
                    clean(helper);
                }));
    }

    /** A 5 m standoff halves... a shell far off does less than a near one. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void blastDamageFallsWithDistance(GameTestHelper helper) {
        float[] near = new float[1];
        SmallArmsArmorGameTest.onLane(helper, LANE + 3, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    burst(helper, v.get(), SHELL, SHELL_SIZE, 2.0, 1.2);
                    near[0] = lost(v.get());
                    burst(helper, v.get(), SHELL, SHELL_SIZE, 7.0, 1.2);
                    float far = lost(v.get());
                    CombinedAssault.LOGGER.info("[blast] humvee side: 2 m {} / 7 m {}", near[0], far);
                    helper.assertTrue(far < near[0] * 0.5f, "7 m cost " + far + ", 2 m cost " + near[0]);
                    clean(helper);
                }));
    }

    /** Jet at contact: M1A2 hull side (60-80 mm) perforated; the turret cheek (published 1150 CE) is not. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aShapedChargeJetResolvesTheFaceItTouches(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 4, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    jet(helper, v.get(), "hull_side", 0.02, 300);
                    helper.assertTrue(SmallArmsArmorGameTest.verdicts(v.get()).stream()
                                    .anyMatch(k -> k.equals("penetration") || k.equals("overmatch")),
                            "jet on hull_side: " + SmallArmsArmorGameTest.verdicts(v.get()));
                    jet(helper, v.get(), "turret_cheek_a", 0.02, 300);
                    helper.assertTrue(SmallArmsArmorGameTest.verdicts(v.get()).stream()
                                    .anyMatch(k -> k.equals("absorbed") || k.equals("partial")),
                            "jet on the cheek: " + SmallArmsArmorGameTest.verdicts(v.get()));
                    clean(helper);
                }));
    }

    /** Same plate, same jet: 5 m of standoff leaves 1 / (1 + 0.6 x 5) = 25 % and it no longer perforates. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void standoffStarvesTheJet(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 5, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    jet(helper, v.get(), "hull_side", 0.02, 300);
                    helper.assertTrue(SmallArmsArmorGameTest.verdicts(v.get()).stream()
                                    .anyMatch(k -> k.equals("penetration") || k.equals("overmatch")),
                            "contact jet on hull_side: " + SmallArmsArmorGameTest.verdicts(v.get()));
                    jet(helper, v.get(), "hull_side", 5.0, 300);
                    helper.assertTrue(SmallArmsArmorGameTest.verdicts(v.get()).stream()
                                    .anyMatch(k -> k.equals("absorbed") || k.equals("partial")),
                            "jet from 5 m: " + SmallArmsArmorGameTest.verdicts(v.get()));
                    clean(helper);
                }));
    }

    private static void jet(GameTestHelper helper, AbstractVehicle vehicle, String plate, double standoff, float pen) {
        Aim aim = SmallArmsArmorGameTest.aimPlate(helper, vehicle, plate);
        Vec3 at = aim.point().add(aim.normal().scale(standoff));
        Vec3 axis = aim.normal().scale(-1);
        new ExplosionAEF(helper.getLevel(), at.x, at.y, at.z, 6.0f, stand(helper, at.add(aim.normal().scale(3))))
                .makeShapedCharge(axis).setBlockAllocator(null)
                .payload(BlastPayload.ofSize(6.0f).withJet(pen, 22.0f)).explode();
    }

    /** HESH 120 mm on the Humvee side: the slab spalls off inside and wounds an occupant; no penetrator. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aSquashHeadSpallsTheCrewBehindThePlate(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 6, HUMVEE, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v.get();
                    SmallArmsArmorGameTest.fresh(vehicle);
                    Aim aim = SmallArmsArmorGameTest.aimPlate(helper, vehicle, "side");
                    Husk crew = seat(helper, vehicle, aim.point().subtract(aim.normal().scale(0.5)));
                    Vec3 at = aim.point().add(aim.normal().scale(0.02));
                    new ExplosionAEF(helper.getLevel(), at.x, at.y, at.z, 4.0f, stand(helper, at.add(0, 5, 0)))
                            .payload(BlastPayload.blast(3.0f).withHesh(120.0f)).setEntityProcessor(new EntityProcessorCross())
                            .setPlayerProcessor(new PlayerProcessorStandard()).explode();
                    float hurt = crew.getMaxHealth() - crew.getHealth();
                    CombinedAssault.LOGGER.info("[blast] humvee side, HESH 120 mm: hull lost {}, occupant lost {}", lost(vehicle), hurt);
                    crew.discard();
                    helper.assertTrue(hurt > 0 || !crew.isAlive(), "the occupant behind the squashed plate took nothing");
                    clean(helper);
                }));
    }

    /** A burst inside the hull: no plate, the occupant takes it and the hull is hurt. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aBurstInsideTheHullReachesTheCrewDirect(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 7, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v.get();
                    SmallArmsArmorGameTest.fresh(vehicle);
                    Vec3 centre = vehicle.getMainCubeOBB().obb().centerVec();
                    Husk crew = seat(helper, vehicle, centre.add(0.6, 0, 0));
                    VehicleBlastResolver.interior(vehicle, stand(helper, centre.add(0, 5, 0)), BlastPayload.blast(0.8f), centre);
                    float hurt = crew.getMaxHealth() - crew.getHealth();
                    CombinedAssault.LOGGER.info("[blast] m1a2 interior burst 0.8 kg: hull lost {}, occupant lost {}", lost(vehicle), hurt);
                    crew.discard();
                    helper.assertTrue(hurt > 0 || !crew.isAlive(), "the occupant inside the burst took nothing");
                    helper.assertTrue(lost(vehicle) > 0, "the hull took nothing from a burst inside it");
                    clean(helper);
                }));
    }

    /** A mine under the belly: the belly plate decides; the carrier's aluminium floor is punched, the tank's is not (charge above the floor: blocks would cover it). */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aBellyMinePunchesTheCarrierNotTheTank(GameTestHelper helper) {
        float[] carrier = new float[1];
        SmallArmsArmorGameTest.onLane(helper, LANE + 8, M113, (seq, v) -> seq
                .thenExecute(() -> {
                    belly(helper, v.get(), 5.0f);
                    carrier[0] = lost(v.get());
                    CombinedAssault.LOGGER.info("[blast] m113 belly, 5 kg mine: hull lost {}", carrier[0]);
                    helper.assertTrue(carrier[0] > 0.02f * v.get().getMaxHealth(), "the carrier floor held a 5 kg mine: " + carrier[0]);
                    clean(helper);
                }));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aBellyMineDoesNotPunchTheTankFloor(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 9, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    belly(helper, v.get(), 5.0f);
                    CombinedAssault.LOGGER.info("[blast] m1a2 belly, 5 kg mine: hull lost {}", lost(v.get()));
                    helper.assertTrue(lost(v.get()) < 0.02f * v.get().getMaxHealth(), "a 5 kg mine cost the tank " + lost(v.get()));
                    clean(helper);
                }));
    }

    private static void belly(GameTestHelper helper, AbstractVehicle vehicle, float kg) {
        SmallArmsArmorGameTest.fresh(vehicle);
        var box = vehicle.getMainCubeOBB().obb();
        // Above the floor: the charge is not buried in a block that would cover its own blast.
        Vec3 at = new Vec3(box.centerX(), Math.max(box.centerY() - box.extents().y - 0.3, vehicle.getY() + 0.05), box.centerZ());
        new ExplosionAEF(helper.getLevel(), at.x, at.y, at.z, 4.0f, stand(helper, at.add(0, 5, 0)))
                .payload(BlastPayload.blast(kg)).setEntityProcessor(new EntityProcessorCross())
                .setPlayerProcessor(new PlayerProcessorStandard()).explode();
    }

    /** Thermite on a seat: a closed hatch shields the occupant, an open one lets the heat in and sets it alight. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void heatReachesOnlyASeatWithAnOpenHatch(GameTestHelper helper) {
        Husk[] crew = new Husk[1];
        float[] before = new float[1];
        AbstractVehicle.Seat[] seat = new AbstractVehicle.Seat[1];
        com.wf.combinedassault.vehicle.part.SwitchableUnit<?>[] hatch = new com.wf.combinedassault.vehicle.part.SwitchableUnit<?>[1];
        SmallArmsArmorGameTest.onLane(helper, LANE + 11, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v.get();
                    crew[0] = seat(helper, vehicle, vehicle.position().add(0, 1.8, 0));
                    for (AbstractVehicle.Seat s : vehicle.seats) {
                        if (s.passengerId == crew[0].getId()) {
                            s.passengerId = -1;
                        }
                        if (seat[0] == null && s.partUnit.hatch() != null) {
                            seat[0] = s;
                        }
                    }
                    helper.assertTrue(seat[0] != null, "no seat with a hatch");
                    seat[0].passengerId = crew[0].getId();
                    hatch[0] = seat[0].partUnit.hatch();
                    hatch[0].setOn(false);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertFalse(v.get().hatchOpen(seat[0]), "the hatch is open after being closed");
                    before[0] = crew[0].getHealth();
                    com.wf.combinedassault.ballistics.ThermalAttacks.onThermal(new com.wf.wflib.damage.api.event.ThermalAttackEvent(
                            v.get(), crew[0].getEyePosition(), 100.0f, true, 0, null, v.get()));
                    helper.assertTrue(crew[0].getHealth() == before[0] && !crew[0].isOnFire(),
                            "heat reached an occupant under a closed hatch: " + before[0] + " -> " + crew[0].getHealth()
                                    + " fire " + crew[0].isOnFire());
                    hatch[0].setOn(true);
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertTrue(v.get().hatchOpen(seat[0]), "the hatch did not open");
                    before[0] = crew[0].getHealth();
                    com.wf.combinedassault.ballistics.ThermalAttacks.onThermal(new com.wf.wflib.damage.api.event.ThermalAttackEvent(
                            v.get(), crew[0].getEyePosition(), 100.0f, true, 0, null, v.get()));
                    helper.assertTrue(crew[0].getHealth() < before[0] && crew[0].isOnFire(),
                            "an occupant under an open hatch took nothing: " + crew[0].getHealth());
                    crew[0].discard();
                    clean(helper);
                }));
    }

    /** One blast pass on the M1A2 vs the 0.54 ms / 1024 rays the panel pass cost alone; the whole resolution after it. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void blastCostIsLogged(GameTestHelper helper) {
        SmallArmsArmorGameTest.onLane(helper, LANE + 10, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle vehicle = v.get();
                    Vec3 centre = vehicle.position().add(5, 1.6, 0);
                    ServerLevel level = helper.getLevel();
                    long ray = Long.MAX_VALUE;
                    long whole = Long.MAX_VALUE;
                    BlastPayload payload = BlastPayload.blast(10.8f).withFragments(1800, 12.0f, 1500.0f);
                    for (int i = 0; i < 40; i++) {
                        long t0 = System.nanoTime();
                        BlastRays.cast(vehicle, centre, SHELL_SIZE, null);
                        long t1 = System.nanoTime();
                        SmallArmsArmorGameTest.fresh(vehicle);
                        net.minecraft.world.level.Explosion explosion = new net.minecraft.world.level.Explosion(level, null,
                                centre.x, centre.y, centre.z, SHELL_SIZE, false, net.minecraft.world.level.Explosion.BlockInteraction.KEEP);
                        long t2 = System.nanoTime();
                        VehicleBlastResolver.resolve(vehicle, explosion, null, payload, centre);
                        long t3 = System.nanoTime();
                        ray = Math.min(ray, t1 - t0);
                        whole = Math.min(whole, t3 - t2);
                    }
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[blast] m1a2: ray pass %d us, whole blast + 1800 fragments %d us (best of 40; ray pass baseline 540 us / 1024 rays)",
                            ray / 1000, whole / 1000));
                    helper.assertTrue(ray < 5_000_000L, "ray pass " + ray + " ns");
                    helper.assertTrue(whole < 20_000_000L, "whole blast " + whole + " ns");
                    clean(helper);
                }));
    }

    private static Husk seat(GameTestHelper helper, AbstractVehicle vehicle, Vec3 at) {
        Husk z = EntityType.HUSK.create(helper.getLevel());
        z.setNoAi(true);
        z.setPos(at);
        helper.getLevel().addFreshEntity(z);
        helper.assertTrue(z.startRiding(vehicle, true), "occupant could not board");
        z.setPos(at);
        return z;
    }
}
