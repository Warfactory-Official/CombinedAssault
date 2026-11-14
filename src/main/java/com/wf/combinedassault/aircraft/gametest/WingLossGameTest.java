package com.wf.combinedassault.aircraft.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotorDebris;
import com.wf.combinedassault.aircraft.WingLoss;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

/** MQ-9 ({@code Rebuilt LEFT/RIGHT main wing}, sided wing pools). */
@PrefixGameTestTemplate(false)
public class WingLossGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MQ9 = CombinedAssault.modLocation("mq9");
    /** Clear of FlapsGameTest (-1700) and AircraftPartsGameTest (-1300 + 64 i). */
    private static final int LANE_X = -2100;
    private static final int LANE_PITCH = 96;
    private static final double CRUISE = 1.5;
    private static final int FLY_TICKS = 20;

    /** Twin MQ-9s at cruise; one loses its left wing => debris, no left lift, rolls left wing down, sinks faster. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aLostWingFallsAwayAndRollsTheAirframeToThatSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos laneA = helper.absolutePos(BlockPos.ZERO).offset(LANE_X, 120, 0);
        BlockPos laneB = laneA.offset(LANE_PITCH, 0, 0);
        force(level, laneA, true);
        force(level, laneB, true);
        FixedWingVehicle whole = spawn(helper, laneA);
        FixedWingVehicle hit = spawn(helper, laneB);
        if (whole == null || hit == null) {
            force(level, laneA, false);
            force(level, laneB, false);
            return;
        }
        double[] startY = new double[2];
        double[] tilt = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(hit.hits() != null && pool(hit, "left wing") >= 0,
                        "mq9 hit model / left wing pool not built"))
                .thenIdle(3)
                .thenExecute(() -> {
                    for (FixedWingVehicle v : List.of(whole, hit)) {
                        cruise(v);
                    }
                    startY[0] = whole.getY();
                    startY[1] = hit.getY();
                    hit.hullComponents().setHealth(pool(hit, "left wing"), 0);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    tilt[0] = wingTipDrop(hit);
                    tilt[1] = wingTipDrop(whole);
                })
                .thenIdle(FLY_TICKS - 3)
                .thenExecute(() -> {
                    float[] wings = WingLoss.intact(hit.hullComponents());
                    int debris = level.getEntitiesOfClass(RotorDebris.class, hit.getBoundingBox().inflate(64)).size();
                    double sinkWhole = startY[0] - whole.getY();
                    double sinkHit = startY[1] - hit.getY();
                    HullComponents pools = hit.hullComponents();
                    int left = pool(hit, "left wing");
                    pools.setHealth(left, pools.maxHealth(left));
                    float[] repaired = WingLoss.intact(pools);
                    whole.discard();
                    hit.discard();
                    level.getEntitiesOfClass(RotorDebris.class, hit.getBoundingBox().inflate(64)).forEach(e -> e.discard());
                    force(level, laneA, false);
                    force(level, laneB, false);
                    String state = String.format(Locale.ROOT,
                            "wings %.0f/%.0f, debris %d, 3 t after: left tip below right by %.2f (intact twin %.2f), sink %.2f vs %.2f, repaired %.0f/%.0f",
                            wings[0], wings[1], debris, tilt[0], tilt[1], sinkHit, sinkWhole, repaired[0], repaired[1]);
                    CombinedAssault.LOGGER.info("[wingloss] {}", state);
                    if (wings[0] != 0 || wings[1] != 1 || debris < 1 || tilt[0] < 1.0 || Math.abs(tilt[1]) > 0.3
                            || sinkHit < sinkWhole + 0.5 || repaired[0] != 1 || repaired[1] != 1) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    /** Stone block inside the hovering left wing => strike check breaks the left pool only. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aWingThatCutsABlockBreaks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(LANE_X + 2 * LANE_PITCH, 120, 0);
        force(level, lane, true);
        FixedWingVehicle mq9 = spawn(helper, lane);
        if (mq9 == null) {
            force(level, lane, false);
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(mq9.hits() != null && pool(mq9, "left wing") >= 0,
                        "mq9 hit model / left wing pool not built"))
                .thenExecute(() -> {
                    BlockPos block = BlockPos.containing(wingCentre(mq9, "left wing"));
                    level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
                    boolean struck = WingLoss.strike(mq9);
                    HullComponents pools = mq9.hullComponents();
                    float left = pools.health(pool(mq9, "left wing"));
                    float right = pools.health(pool(mq9, "right wing"));
                    level.setBlockAndUpdate(block, Blocks.AIR.defaultBlockState());
                    mq9.discard();
                    force(level, lane, false);
                    if (!struck || left != 0 || right <= 0) {
                        helper.fail(String.format(Locale.ROOT, "block in the left wing: struck %s, left wing %.0f, right wing %.0f",
                                struck, left, right));
                    }
                })
                .thenSucceed();
    }

    private static void cruise(FixedWingVehicle v) {
        v.setEnergy(v.energyInfo.capacityUnits());
        v.setPower(100);
        v.throttleLevel = 100;
        v.setDeltaMovement(v.getLookAngle().scale(CRUISE));
    }

    private static int pool(FixedWingVehicle v, String id) {
        HullComponents pools = v.hullComponents();
        int i = pools.indexOf(id);
        return i >= 0 && pools.kind(i) == ComponentKind.WING ? i : -1;
    }

    /** Left wing centre minus right wing centre, world y, negated: + = left tip low. */
    private static double wingTipDrop(FixedWingVehicle v) {
        return wingCentre(v, "right wing").y - wingCentre(v, "left wing").y;
    }

    /** Mean world centre of the pool's drawn wing nodes. */
    private static Vec3 wingCentre(FixedWingVehicle v, String pool) {
        VehicleHits hits = v.hits();
        List<HitModel.Node> nodes = hits.model().nodes();
        Matrix4f pose = new Matrix4f();
        Vector3f sum = new Vector3f();
        int n = 0;
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            if (node.kind() != ComponentKind.WING || node.mesh().mesh() == null || !pool.equals(node.systemId())) {
                continue;
            }
            float[] b = node.bounds();
            hits.pose(i, v.position(), pose);
            sum.add(pose.transformPosition(new Vector3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)));
            n++;
        }
        if (n == 0) {
            throw new IllegalStateException(v.getDisplayId() + " has no drawn " + pool + " nodes");
        }
        sum.div(n);
        return v.position().add(sum.x, sum.y, sum.z);
    }

    @Nullable
    private static FixedWingVehicle spawn(GameTestHelper helper, BlockPos at) {
        java.util.Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MQ9);
        if (data.isEmpty()) {
            helper.fail(MQ9 + " is not in the roster");
            return null;
        }
        if (!(data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0)
                instanceof FixedWingVehicle mq9)) {
            helper.fail(MQ9 + " is not a fixed-wing vehicle");
            return null;
        }
        mq9.setDisplayId(MQ9);
        if (!helper.getLevel().addFreshEntity(mq9)) {
            helper.fail(MQ9 + " could not be added to the world");
            return null;
        }
        return mq9;
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= 6; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }
}
