package com.wf.combinedassault.vehicle.crew.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uncrewed: seat 0 of the Centauro is the turret; boarding and driving go to the declared driver seat. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class CrewSeatGameTest {

    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void theFirstAboardTakesTheDriverSeat(GameTestHelper helper) {
        if (CrewRules.enabled()) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 4, 1)));
        AbstractVehicle tank = CommonAssetsManager.vehicleDataManager()
                .getVehicleData(CombinedAssault.modLocation("centauro")).orElseThrow().construct(level, at, 0, 0);
        tank.setDisplayId(CombinedAssault.modLocation("centauro"));
        tank.setNoGravity(true);
        level.addFreshEntity(tank);
        ArmorStand first = new ArmorStand(level, at.x, at.y, at.z);
        ArmorStand second = new ArmorStand(level, at.x, at.y, at.z);
        level.addFreshEntity(first);
        level.addFreshEntity(second);
        first.startRiding(tank, true);
        second.startRiding(tank, true);
        String boarded = seat(tank, first) + " / " + seat(tank, second) + ", driver " + name(tank.getDriver(), first, second);
        first.stopRiding();
        String alone = seat(tank, second) + ", driver " + name(tank.getDriver(), first, second);
        tank.discard();
        first.discard();
        second.discard();
        if (!boarded.equals("driver / turret, driver first") || !alone.equals("turret, driver second")) {
            helper.fail("boarded " + boarded + "; driver gone: " + alone);
            return;
        }
        helper.succeed();
    }

    private static String seat(AbstractVehicle vehicle, LivingEntity rider) {
        var unit = vehicle.getOwnOperatorUnit(rider);
        return unit == null ? "none" : unit.getData().getId();
    }

    private static String name(LivingEntity driver, LivingEntity first, LivingEntity second) {
        return driver == first ? "first" : driver == second ? "second" : String.valueOf(driver);
    }
}
