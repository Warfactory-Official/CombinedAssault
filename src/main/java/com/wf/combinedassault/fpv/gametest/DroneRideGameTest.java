package com.wf.combinedassault.fpv.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.payload.PayloadUnit;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Regression: tick-0 anchor skip + first-tick rider-clear together dismounted a freshly connected
 * operator beside the drone instead of into their own body.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class DroneRideGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation DRONE = CombinedAssault.modLocation("fpv_drone");

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void droneDataLoads(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(DRONE);
        if (data.isEmpty()) {
            helper.fail("the fpv drone is not in the vehicle roster; it should be a stock vehicle now");
            return;
        }
        if (!data.get().isUav()) {
            helper.fail("the drone must be a uav, or the controller cannot pair with it");
            return;
        }
        helper.succeed();
    }

    /** Uses {@code onEnterVehicle} not {@code startRiding}: FakePlayer fails the mount gate on a dedicated server. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void connectingToAFreshDroneRecordsTheOperatorsBody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AbstractVehicle drone = spawnDrone(helper, 30);
        if (drone == null) {
            return;
        }
        BlockPos bodyPos = helper.absolutePos(new BlockPos(1, 1, 1));
        Vec3 body = new Vec3(bodyPos.getX() + 0.5, bodyPos.getY(), bodyPos.getZ() + 0.5);

        FakePlayer operator = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "fpv_operator"));
        operator.moveTo(body.x, body.y, body.z, 0f, 0f);

        if (drone.tickCount != 0) {
            helper.fail("the drone was expected to be on its first tick, was on " + drone.tickCount);
            return;
        }
        drone.onEnterVehicle(operator);
        Vec3 anchor = drone.getDetachedBodyAnchor(operator);
        if (anchor == null) {
            helper.fail("connecting to a uav must record where the operator's body is, on any tick");
            return;
        }
        if (anchor.distanceTo(body) > 0.01) {
            helper.fail("the anchor is at " + anchor + ", the operator's body is at " + body);
            return;
        }
        Vec3 dismount = drone.getDismountLocationForPassenger(operator);
        if (dismount.distanceTo(body) > 1.0) {
            helper.fail("dismounting put the operator at " + dismount + " instead of their body at " + body
                    + " (the drone is at " + drone.position() + ")");
            return;
        }
        drone.discard();
        operator.discard();
        helper.succeed();
    }

    /** First tick clears riders (stale: restored/spawned-with-passengers); a connected operator must survive it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void firstTickKeepsAConnectedOperatorAndClearsStaleRiders(GameTestHelper helper) {
        AbstractVehicle stale = spawnDrone(helper, 20);
        AbstractVehicle connected = spawnDrone(helper, 40);
        if (stale == null || connected == null) {
            return;
        }
        ArmorStand staleRider = rider(helper, stale);
        ArmorStand connectedRider = rider(helper, connected);
        if (!staleRider.startRiding(stale) || !connectedRider.startRiding(connected)) {
            helper.fail("the test riders could not board");
            return;
        }
        connected.setDetachedBodyAnchor(connectedRider, helper.absoluteVec(new Vec3(1, 1, 1)));

        helper.runAfterDelay(4, () -> {
            if (staleRider.getVehicle() == stale) {
                helper.fail("a rider that came attached should have been cleared on the first tick");
                return;
            }
            if (connectedRider.getVehicle() != connected) {
                helper.fail("a connected operator was thrown off by the drone's first tick");
                return;
            }
            stale.discard();
            connected.discard();
            staleRider.discard();
            connectedRider.discard();
            helper.succeed();
        });
    }

    @Nullable
    private static AbstractVehicle spawnDrone(GameTestHelper helper, int height) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(DRONE);
        if (data.isEmpty()) {
            helper.fail("the fpv drone is not in the vehicle roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, height, 1));
        AbstractVehicle drone = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        drone.setDisplayId(DRONE);
        if (!helper.getLevel().addFreshEntity(drone)) {
            helper.fail("the drone could not be added to the world");
            return null;
        }
        return drone;
    }

    private static ArmorStand rider(GameTestHelper helper, AbstractVehicle drone) {
        ArmorStand stand = new ArmorStand(helper.getLevel(), drone.getX(), drone.getY(), drone.getZ());
        stand.setNoGravity(true);
        helper.getLevel().addFreshEntity(stand);
        return stand;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void payloadIsNotLiveUntilItsFuzeHasRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(DRONE);
        if (data.isEmpty()) {
            helper.fail("the fpv drone is not in the vehicle roster");
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 20, 1));
        AbstractVehicle drone = data.get().construct(level, new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        drone.setDisplayId(DRONE);
        level.addFreshEntity(drone);

        PayloadUnit hardpoint = PayloadUnit.on(drone);
        if (hardpoint == null) {
            helper.fail("the drone's hardpoint did not build; check the part unit type registration");
            return;
        }
        if (!hardpoint.isLoaded()) {
            helper.fail("the hardpoint came up empty; check the payload type id in the vehicle json");
            return;
        }
        if (hardpoint.isArmed() || hardpoint.isLive()) {
            helper.fail("a drone must not arrive armed");
            return;
        }
        int fuze = hardpoint.armingTicks();
        if (fuze <= 0) {
            helper.fail("the warhead has no arming delay, so it can be set off at the operator's feet");
            return;
        }
        hardpoint.setOn(true);
        if (!hardpoint.isArmed() || hardpoint.isLive()) {
            helper.fail("arming must start the fuze, not finish it");
            return;
        }

        helper.runAfterDelay(fuze / 2, () -> {
            if (hardpoint.isLive()) {
                helper.fail("the charge went live after half its fuze");
                return;
            }
            hardpoint.setOn(false);
            hardpoint.setOn(true);
            if (hardpoint.armingRemaining() != fuze) {
                helper.fail("re-arming left " + hardpoint.armingRemaining() + " of the fuze, expected " + fuze);
            }
        });

        helper.runAfterDelay(fuze / 2 + fuze + 4, () -> {
            if (!hardpoint.isLive()) {
                helper.fail("the charge never went live; " + hardpoint.armingRemaining() + " ticks still to run");
                return;
            }
            drone.discard();
            helper.succeed();
        });
    }

}
