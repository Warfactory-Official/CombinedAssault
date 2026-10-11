package com.wf.combinedassault.api.collision.gametest;

import com.wf.combinedassault.gametest.TestLanes;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.api.collision.CollisionProvider;
import com.wf.combinedassault.api.collision.CollisionProviders;
import com.wf.combinedassault.api.collision.OrientedBoxes;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.Locale;

/** Provider geometry is oriented and solid; loaded boxes reach the session. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class CollisionProviderGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_X = 2400;
    private static final int HALF = 8;
    private static final float TILT = (float) Math.toRadians(20);

    /** A plank pitched 20 degrees: its AABB would hold the hull level, the box itself pitches it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void aVehicleRestsOnATiltedProviderBox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 0, 0);
        force(level, start, true);
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        Vec3 centre = new Vec3(start.getX() + 0.5, start.getY() + 4, start.getZ() + 0.5);
        Plank plank = new Plank(centre, new Quaternionf().rotateX(TILT));
        CollisionProviders.register(plank);
        AbstractVehicle[] spawned = new AbstractVehicle[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(start), "lane not entity-ticking yet"))
                .thenExecute(() -> spawned[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("humvee"), centre.add(0, 3, 0)))
                .thenIdle(80)
                .thenExecute(() -> {
                    AbstractVehicle truck = spawned[0];
                    CollisionProviders.unregister(plank);
                    double pitch = Math.toDegrees(Math.asin(-truck.getMainCubeOBB().obb().getAxes()[2].y));
                    double height = truck.getY() - start.getY();
                    String report = String.format(Locale.ROOT, "hull pitch %.1f, %.2f above the floor, %d loaded box reports, %d ends",
                            pitch, height, plank.touched, plank.ended);
                    truck.discard();
                    for (int x = -HALF; x <= HALF; x++) {
                        for (int z = -HALF; z <= HALF; z++) {
                            level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                    force(level, start, false);
                    if (Math.abs(Math.abs(pitch) - 20) > 3 || height < 2 || plank.touched == 0 || plank.ended == 0) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    private static void force(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF) >> 4; cx <= (start.getX() + HALF) >> 4; cx++) {
            for (int cz = (start.getZ() - HALF) >> 4; cz <= (start.getZ() + HALF) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }

    /** 12 x 0.5 x 8 box, one per lane. */
    private static final class Plank implements CollisionProvider, CollisionProvider.Session {

        private static final float EX = 4, EY = 0.25f, EZ = 6;
        private final Vec3 centre;
        private final Quaternionf rotation;
        private final AABB reach;
        int touched, ended;

        Plank(Vec3 centre, Quaternionf rotation) {
            this.centre = centre;
            this.rotation = rotation;
            this.reach = new AABB(centre, centre).inflate(EZ + 1);
        }

        @Nullable
        @Override
        public Session begin(AbstractVehicle vehicle, AABB hullBounds) {
            return hullBounds.intersects(reach) ? this : null;
        }

        @Override
        public void collectBoxes(AABB bounds, OrientedBoxes out) {
            out.add(centre.x, centre.y, centre.z, rotation, EX, EY, EZ);
        }

        @Override
        public void touched(int box) {
            touched++;
        }

        @Override
        public void end() {
            ended++;
        }
    }
}
