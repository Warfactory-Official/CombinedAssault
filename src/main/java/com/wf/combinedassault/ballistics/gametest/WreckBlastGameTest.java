package com.wf.combinedassault.ballistics.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** M1A2 killed through {@code hurt}: what its wreck blast does to pigs (open, walled), a parked Humvee, itself. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class WreckBlastGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE = -7400;
    private static final float PIG_HEALTH = 1000;
    /** Pig offsets from the tank, blocks along +x; tank ~1.8 each side; wall 1 block thick at -3.5..-2.5. */
    private static final double[] OPEN = {2.5, 3.5, 6, 9, 14};
    private static final double WALLED = -4.5;

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aWreckBlastHurtsWhatStandsNearItAndNotItself(GameTestHelper helper) {
        List<Pig> pigs = new ArrayList<>();
        AbstractVehicle[] parked = new AbstractVehicle[1];
        float[] before = new float[2];
        BlockPos[] wall = new BlockPos[1];
        TestLanes.run(helper, LANE, 16, 16, CombinedAssault.modLocation("m1a2"), (seq, vehicle) -> seq
                .thenExecute(() -> {
                    AbstractVehicle tank = vehicle.get();
                    BlockPos base = BlockPos.containing(tank.position());
                    wall[0] = base;
                    for (int y = 0; y < 4; y++) {
                        for (int z = -2; z <= 2; z++) {
                            helper.getLevel().setBlockAndUpdate(base.offset(-3, y, z), Blocks.STONE.defaultBlockState());
                        }
                    }
                    for (double dx : OPEN) {
                        pigs.add(pig(helper, tank.position().add(dx, 0, 0)));
                    }
                    pigs.add(pig(helper, tank.position().add(WALLED, 0, 0)));
                    parked[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("humvee"), tank.position().add(0, 0, -8));
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    AbstractVehicle tank = vehicle.get();
                    before[0] = tank.getHealth();
                    before[1] = parked[0].getHealth();
                    pigs.forEach(p -> p.setDeltaMovement(Vec3.ZERO));
                    for (VehicleSystems.Cell cell : VehicleSystems.cells(tank)) {
                        cell.members().forEach(m -> m.setHealth(0));
                    }
                    tank.hurt(helper.getLevel().damageSources().generic(), 1);
                    helper.assertTrue(tank.isDestroyed(), "the tank survived the kill");
                    StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "radius %.2f;",
                            tank.mainBox().extents().z));
                    for (int i = 0; i < pigs.size(); i++) {
                        Pig p = pigs.get(i);
                        out.append(String.format(Locale.ROOT, " pig %s%.1f: damage %.1f push %.3f;",
                                i < OPEN.length ? "" : "walled ", i < OPEN.length ? OPEN[i] : WALLED,
                                PIG_HEALTH - p.getHealth(), p.getDeltaMovement().horizontalDistance()));
                    }
                    out.append(String.format(Locale.ROOT, " humvee: damage %.1f destroyed %s removed %s;",
                            before[1] - parked[0].getHealth(), parked[0].isDestroyed(), parked[0].isRemoved()));
                    out.append(String.format(Locale.ROOT, " wreck: damage %.1f removed %s",
                            before[0] - tank.getHealth(), tank.isRemoved()));
                    CombinedAssault.LOGGER.info("[wreckblast] {}", out);
                    helper.assertFalse(tank.isRemoved(), "the wreck blew itself away: " + out);
                    helper.assertTrue(before[0] - tank.getHealth() == 0, "the wreck hurt itself: " + out);
                    helper.assertTrue(PIG_HEALTH - pigs.get(0).getHealth() > 0, "the nearest pig was untouched: " + out);
                    helper.assertTrue(PIG_HEALTH - pigs.get(OPEN.length - 1).getHealth() == 0,
                            "a pig " + OPEN[OPEN.length - 1] + " blocks out was hurt: " + out);
                }), at -> {
                    pigs.forEach(Pig::discard);
                    if (parked[0] != null) {
                        parked[0].discard();
                    }
                    helper.getLevel().getEntitiesOfClass(VehiclePart.class, new AABB(at).inflate(24)).forEach(VehiclePart::discard);
                    BlockPos base = wall[0];
                    for (int y = 0; y < 4; y++) {
                        for (int z = -2; z <= 2; z++) {
                            helper.getLevel().setBlockAndUpdate(base.offset(-3, y, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                });
    }

    private static Pig pig(GameTestHelper helper, Vec3 at) {
        Pig pig = EntityType.PIG.create(helper.getLevel());
        pig.setNoAi(true);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(PIG_HEALTH);
        pig.setHealth(PIG_HEALTH);
        pig.moveTo(at.x, at.y, at.z, 0, 0);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }
}
