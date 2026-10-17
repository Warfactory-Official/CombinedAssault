package com.wf.combinedassault.multirotor.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Amazog octocopter: eight rotor pools, hover on seven, >= 20 kg delivery load, stick cruise at its own (lower) `max_speed`. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class AmazogGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation OCTO = CombinedAssault.modLocation("amazog");

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void amazogHoversOnSevenRotors(GameTestHelper helper) {
        RotaryWingVehicle octo = MultirotorGameTest.spawn(helper, OCTO, 1, 30);
        if (octo == null) {
            return;
        }
        octo.multirotor.setFpv(false);
        helper.runAfterDelay(2, () -> {
            HullComponents systems = octo.hullComponents();
            int found = 0;
            for (int i = 1; i <= 8; i++) {
                found += systems.indexOf("rotor R" + i) >= 0 ? 1 : 0;
            }
            int r1 = systems.indexOf("rotor R1");
            systems.setHealth(r1, 0);
            double share = octo.rotorShare();
            Vec3 start = octo.position();
            int pools = found;
            helper.runAfterDelay(80, () -> {
                Vec3 d = octo.position().subtract(start);
                octo.discard();
                helper.assertTrue(pools == 8, "rotor pools " + pools);
                helper.assertTrue(share > 0 && share < 1, "share with R1 dead " + share);
                helper.assertTrue(Math.abs(d.y) < 0.5 && d.horizontalDistance() < 0.5, "hover drift " + d);
                helper.succeed();
            });
        });
    }

    /** Delivery load: 3 iron blocks (~23.6 kg) within `max_payload`, full throttle still climbs. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void amazogLiftsTwentyKilograms(GameTestHelper helper) {
        RotaryWingVehicle octo = MultirotorGameTest.spawn(helper, OCTO, 1, 30);
        if (octo == null) {
            return;
        }
        octo.multirotor.setFpv(false);
        octo.setItem(0, new ItemStack(Items.IRON_BLOCK, 3));
        double[] y0 = new double[1];
        helper.runAfterDelay(30, () -> {
            octo.multirotor.setFpv(true);
            octo.setCollectivePitch(100);
            octo.controlUnit.xRot = 0;
            octo.controlUnit.yRotKeep = true;
            y0[0] = octo.getY();
        });
        helper.runAfterDelay(50, () -> {
            double climb = octo.getY() - y0[0];
            double cargo = octo.cargoMass();
            double over = octo.overload();
            octo.discard();
            helper.assertTrue(cargo >= 20, "cargo " + cargo + " kg");
            helper.assertTrue(over <= 1, "overload " + over);
            helper.assertTrue(climb > 1, "20 ticks full throttle with " + cargo + " kg climbed " + climb);
            helper.succeed();
        });
    }

    /** Own lane at z -2600 (Mavic's cruise test owns -2400). */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void amazogCruisesAtItsSlowerStick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(new BlockPos(1, 40, 1)).offset(0, 0, -2600);
        int cx = lane.getX() >> 4;
        int cz0 = lane.getZ() >> 4;
        for (int cz = cz0 - 1; cz <= cz0 + 6; cz++) {
            ForcedChunks.set(level, cx, cz, true);
        }
        RotaryWingVehicle[] octo = new RotaryWingVehicle[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(lane), "lane not ticking"))
                .thenExecute(() -> {
                    octo[0] = MultirotorGameTest.spawnAt(helper, OCTO, lane);
                    octo[0].multirotor.setFpv(false);
                    octo[0].controlUnit.forward = true;
                    octo[0].controlUnit.yRotKeep = true;
                })
                .thenIdle(160)
                .thenExecute(() -> {
                    RotaryWingVehicle o = octo[0];
                    double speed = o.getDeltaMovement().horizontalDistance();
                    o.discard();
                    for (int cz = cz0 - 1; cz <= cz0 + 6; cz++) {
                        ForcedChunks.set(level, cx, cz, false);
                    }
                    helper.assertTrue(Math.abs(speed - 0.45) < 0.05, "cruise " + speed + " b/t, stick asks 0.45");
                })
                .thenSucceed();
    }
}
