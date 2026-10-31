package com.wf.combinedassault.vehicle.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.Locale;

/**
 * Aircraft killed in the air: wreck turns out of its kill attitude on the ground, comes to rest. Ground vehicle killed
 * moving (user: hulls slide when they explode): wreck stops like a dragged hull. Lane forces +-16 blocks.
 */
@PrefixGameTestTemplate(false)
public class WreckGameTest {

    private static final String TEMPLATE = "empty";
    private static final int DROP = 18;
    private static final int REST = 100;
    /** Blocks/tick (6 m/s). */
    private static final double KILL_SPEED = 0.3;
    /** Slide limit, blocks. Humvee measured 2.7 alone, 6.1 under full-suite load; free roll 27.6. */
    private static final double SLIDE = 10;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aJetKilledInADiveDoesNotStayWedgedNoseDown(GameTestHelper helper) {
        wreck(helper, -8400, CombinedAssault.modLocation("a10"), 30, 0, new Vec3(0, -0.4, 0.3),
                v -> Math.abs(Mth.wrapDegrees(v.getXRot())) < 15,
                v -> "pitch " + v.getXRot());
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aHelicopterKilledBankedDoesNotLandFrozenAtItsBank(GameTestHelper helper) {
        wreck(helper, -8600, CombinedAssault.modLocation("ka52"), 0, 45, Vec3.ZERO,
                v -> Math.abs(Mth.wrapDegrees(v.getZRot()) - 45) > 10,
                v -> "roll " + v.getZRot());
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aTruckKilledMovingStopsOnItsBelly(GameTestHelper helper) {
        groundWreck(helper, -6000, CombinedAssault.modLocation("humvee"));
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aTankKilledMovingStopsOnItsBelly(GameTestHelper helper) {
        groundWreck(helper, -6200, CombinedAssault.modLocation("m1a2"));
    }

    /** Killed through {@code hurt} at {@link #KILL_SPEED} forwards; mu 0.5 drag => {@code v^2 / 2 mu g} ~ 3.7 blocks; frictionless => off the floor. */
    private static void groundWreck(GameTestHelper helper, int lane, ResourceLocation id) {
        Vec3[] killedAt = new Vec3[1];
        TestLanes.run(helper, lane, 12, 16, id, (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    v.setPos(v.getX(), v.getY(), v.getZ() - 10);
                    killedAt[0] = v.position();
                    v.setDeltaMovement(0, 0, KILL_SPEED);
                    for (VehicleSystems.Cell cell : VehicleSystems.cells(v)) {
                        cell.members().forEach(m -> m.setHealth(0));
                    }
                    v.hurt(helper.getLevel().damageSources().generic(), 1);
                    helper.assertTrue(v.isDestroyed(), id + " survived the kill");
                })
                .thenIdle(REST)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    Vec3 slid = v.position().subtract(killedAt[0]);
                    String state = String.format(Locale.ROOT, "%s slid %.2f (x %.2f z %.2f), speed %.3f, yaw %.1f",
                            id, slid.horizontalDistance(), slid.x, slid.z, v.getDeltaMovement().horizontalDistance(),
                            Mth.wrapDegrees(v.getYRot()));
                    CombinedAssault.LOGGER.info("[wreck] {}", state);
                    helper.assertTrue(v.getDeltaMovement().horizontalDistance() < 0.01, "wreck still sliding: " + state);
                    helper.assertTrue(slid.horizontalDistance() < SLIDE, "wreck slid: " + state);
                }), at -> helper.getLevel().getEntitiesOfClass(VehiclePart.class, new net.minecraft.world.phys.AABB(at).inflate(24))
                .forEach(VehiclePart::discard));
    }

    private static void wreck(GameTestHelper helper, int lane, ResourceLocation id, float pitch, float roll, Vec3 velocity,
                              java.util.function.Predicate<AbstractVehicle> turned,
                              java.util.function.Function<AbstractVehicle, String> attitude) {
        double[] floorY = new double[1];
        TestLanes.run(helper, lane, 24, 48, id, (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    floorY[0] = v.getY();
                    v.setPos(v.getX(), v.getY() + DROP, v.getZ() - 6);
                    v.setXRot(pitch);
                    v.setZRot(roll);
                    v.setDeltaMovement(velocity);
                    v.setDestroyed();
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicle.get().getY() < floorY[0] + 3, id + " never landed"))
                .thenIdle(REST)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    double speed = v.getDeltaMovement().length();
                    String state = id + " " + attitude.apply(v) + ", speed " + speed + ", y " + (v.getY() - floorY[0]);
                    helper.assertTrue(turned.test(v), "wreck held its kill attitude: " + state);
                    helper.assertTrue(speed < 0.1, "wreck still moving: " + state);
                    helper.assertTrue(v.getY() > floorY[0] - 1, "wreck sank: " + state);
                }), at -> {});
    }
}
