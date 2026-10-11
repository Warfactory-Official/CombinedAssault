package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Unsprung hull dropped 2 blocks onto stone (own lane): still by tick 80 (user: Phalanx bounced on settling). */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SettleGameTest {

    private static final String TEMPLATE = "empty";
    /** Clear of arenas and SuspensionGameTest lanes (z + 400 ..). */
    private static final int LANE_Z = 1400;
    private static final int HALF = 6;
    private static final int TICKS = 160;
    private static final int STILL_FROM = 80;

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aDroppedPhalanxSettles(GameTestHelper helper) {
        drop(helper, "mk15", 0);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aDroppedMortarSettles(GameTestHelper helper) {
        drop(helper, "m252a1", 1);
    }

    private static void drop(GameTestHelper helper, String name, int lane) {
        ResourceLocation id = CombinedAssault.modLocation(name);
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * 32);
        forced(level, centre, true);
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                level.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        double floor = centre.getY() + 1;
        AbstractVehicle[] vehicle = new AbstractVehicle[1];
        List<String> trace = new ArrayList<>();
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(centre), "lane not ticking yet"))
                .thenExecute(() -> {
                    vehicle[0] = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                            .construct(level, new Vec3(centre.getX() + 0.5, floor + 2, centre.getZ() + 0.5), 0, 0);
                    vehicle[0].setDisplayId(id);
                    level.addFreshEntity(vehicle[0]);
                });
        for (int t = 1; t <= TICKS; t++) {
            int tick = t;
            sequence = sequence.thenIdle(1).thenExecute(() -> {
                AbstractVehicle v = vehicle[0];
                float[] s = {(float) (v.getY() - floor), Mth.wrapDegrees(v.getXRot()), Mth.wrapDegrees(v.getZRot())};
                var w = v.physicsEngine.angularVelocity;
                trace.add(String.format(Locale.ROOT, "t%d y%.4f p%.3f r%.3f w(%.4f,%.4f,%.4f) g%s", tick, s[0], s[1],
                        s[2], w.x, w.y, w.z, v.onGround() ? 1 : 0));
                if (tick >= STILL_FROM) {
                    for (int k = 0; k < 3; k++) {
                        lo[k] = Math.min(lo[k], s[k]);
                        hi[k] = Math.max(hi[k], s[k]);
                    }
                }
            });
        }
        sequence.thenExecute(() -> {
            AbstractVehicle v = vehicle[0];
            double gap = v.getMainCubeOBB().obb().centerY() - v.getMainCubeOBB().obb().extents().y - floor;
            float pitch = Mth.wrapDegrees(v.getXRot());
            float roll = Mth.wrapDegrees(v.getZRot());
            v.discard();
            for (int x = -HALF; x <= HALF; x++) {
                for (int z = -HALF; z <= HALF; z++) {
                    level.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                }
            }
            forced(level, centre, false);
            float dy = hi[0] - lo[0], dp = hi[1] - lo[1], dr = hi[2] - lo[2];
            if (dy > 1e-3f || dp > 0.05f || dr > 0.05f) {
                helper.fail(String.format(Locale.ROOT, "%s still moving after tick %d: y %.4f, pitch %.3f, roll %.3f; %s",
                        name, STILL_FROM, dy, dp, dr, String.join(" ", trace)));
            } else if (Math.abs(gap) > 0.02 || Math.abs(pitch) > 0.5f || Math.abs(roll) > 0.5f) {
                helper.fail(String.format(Locale.ROOT, "%s at rest off its floor: underside %+.4f, pitch %.2f, roll %.2f",
                        name, gap, pitch, roll));
            }
        }).thenSucceed();
    }

    private static void forced(ServerLevel level, BlockPos centre, boolean on) {
        for (int cx = (centre.getX() - HALF) >> 4; cx <= (centre.getX() + HALF) >> 4; cx++) {
            for (int cz = (centre.getZ() - HALF) >> 4; cz <= (centre.getZ() + HALF) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, on);
            }
        }
    }
}
