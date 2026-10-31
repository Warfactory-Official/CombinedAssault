package com.wf.combinedassault.vehicle.compat.sable.gametest;

import com.wf.wflib.testkit.api.ForcedChunks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.Locale;

/** Registered from {@code SableCompat.init} only with Sable present: NeoForge class-loads every {@code @GameTestHolder}. */
@PrefixGameTestTemplate(false)
public class SableProviderGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_X = 2440;
    private static final int HALF = 10;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 260)
    public static void aVehicleRestsOnAYawedSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 0, 0);
        force(level, start, true);
        fill(level, start, true);
        AbstractVehicle[] trucks = new AbstractVehicle[2];
        ServerSubLevel[] platform = new ServerSubLevel[1];
        // Held at its resting pose: under suite load Sable dropped it through the floor (y -337, alone 1 run in 5).
        helper.onEachTick(() -> {
            if (platform[0] != null) {
                hold(level, platform[0], start);
            }
        });
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> platform[0] = platform(level, start))
                .thenIdle(60)
                .thenExecute(() -> {
                    trucks[0] = spawn(level, new Vec3(start.getX() + 0.5, start.getY() + 5, start.getZ() + 0.5));
                    trucks[1] = spawn(level, new Vec3(start.getX() + 0.5, start.getY() + 2, start.getZ() - 8.5));
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    double lift = trucks[0].getY() - trucks[1].getY();
                    var pose = platform[0].logicalPose();
                    String report = String.format(Locale.ROOT, "truck on the platform %.2f above the one on the floor; platform at %s %s, truck %s pitch %.1f roll %.1f",
                            lift, pose.position(), pose.orientation(), trucks[0].position().subtract(Vec3.atLowerCornerOf(start)), trucks[0].getXRot(), trucks[0].getZRot());
                    for (AbstractVehicle truck : trucks) {
                        truck.discard();
                    }
                    fill(level, start, false);
                    force(level, start, false);
                    if (Math.abs(lift - 1) > 0.25) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    private static ServerSubLevel platform(ServerLevel level, BlockPos start) {
        BlockPos min = start.offset(-3, 3, -4), max = start.offset(3, 3, 4);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        ServerSubLevel platform = SubLevelAssemblyHelper.assembleBlocks(level, min, BlockPos.betweenClosed(min, max),
                new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        hold(level, platform, start);
        return platform;
    }

    /** On the floor (top start.y + 1), yawed 45. */
    private static void hold(ServerLevel level, ServerSubLevel platform, BlockPos start) {
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        pipeline.teleport(platform, new Vector3d(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 0.5),
                new Quaterniond().rotateY(Math.toRadians(45)));
        pipeline.resetVelocity(platform);
    }

    private static AbstractVehicle spawn(ServerLevel level, Vec3 at) {
        AbstractVehicle truck = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation("humvee"))
                .orElseThrow().construct(level, at, 0, 0);
        truck.setDisplayId(CombinedAssault.modLocation("humvee"));
        level.addFreshEntity(truck);
        return truck;
    }

    private static void fill(ServerLevel level, BlockPos start, boolean stone) {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), (stone ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
        }
    }

    private static void force(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF) >> 4; cx <= (start.getX() + HALF) >> 4; cx++) {
            for (int cz = (start.getZ() - HALF) >> 4; cz <= (start.getZ() + HALF) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}
