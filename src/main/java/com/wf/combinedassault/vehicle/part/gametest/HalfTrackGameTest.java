package com.wf.combinedassault.vehicle.part.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.combinedassault.dynamics.part.TrackUnit;
import com.wf.combinedassault.dynamics.part.data.TrackUnitData;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.data.WheeledVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.WheeledVehicle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every loaded half-track ({@code wheeled_vehicle} with {@code track} parts), own flat lane each. None loaded (shipped
 * packs have none) => vacuous; `-PoptionalPacks=<ns>` brings converted ones in.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HalfTrackGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_X = -7000;
    private static final int LANE_PITCH = 40;
    private static final int HALF_WIDTH = 16;
    private static final int LENGTH = 160;
    private static final int SETTLE = 40;
    private static final int DRIVE = 80;
    /** Ticks averaged per belt-vs-ground sample. */
    private static final int WINDOW = 10;

    private record Lane(ResourceLocation id, BlockPos start, Vec3 spawn, AbstractVehicle vehicle, ArmorStand driver) {
    }

    private interface Body {
        GameTestSequence run(GameTestSequence seq, List<Lane> lanes, List<String> problems);
    }

    /** Throttle only: all wheels and road wheels down, heading held, belts at the measured ground speed of each track. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void halfTrackDrivesStraightWithBeltsAtGroundSpeed(GameTestHelper helper) {
        run(helper, 0, (seq, lanes, problems) -> {
            float[] yaw0 = new float[lanes.size()];
            seq = seq.thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    yaw0[i] = lane.vehicle.getYRot();
                    groundedCheck(lane, problems);
                    lane.vehicle.controlUnit.forward = true;
                }
            }).thenIdle(DRIVE);
            seq = belts(seq, lanes, problems);
            return seq.thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    groundedCheck(lane, problems);
                    double travelled = lane.vehicle.position().subtract(lane.spawn).horizontalDistance();
                    float drift = Math.abs(Mth.wrapDegrees(lane.vehicle.getYRot() - yaw0[i]));
                    if (travelled < 10) {
                        problems.add(lane.id + " travelled " + fmt(travelled));
                    }
                    if (drift > 3) {
                        problems.add(lane.id + " drifted " + fmt(drift) + " deg off a straight run");
                    }
                }
            });
        });
    }

    /** Rolling + left lock: front wheels steer, nose swings left, inner (left) belt slower; standing + lock: no pivot. */
    @GameTest(template = TEMPLATE, timeoutTicks = 500)
    public static void halfTrackSteersWithFrontWheelsNotTracks(GameTestHelper helper) {
        run(helper, 1, (seq, lanes, problems) -> {
            float[] yaw = new float[lanes.size()];
            seq = seq.thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    yaw[i] = lane.vehicle.getYRot();
                    lane.vehicle.controlUnit.left = true;
                }
            }).thenIdle(40).thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    float turned = Mth.wrapDegrees(lane.vehicle.getYRot() - yaw[i]);
                    if (Math.abs(turned) > 0.5) {
                        problems.add(lane.id + " pivoted " + fmt(turned) + " deg standing");
                    }
                    lane.vehicle.controlUnit.left = false;
                    lane.vehicle.controlUnit.forward = true;
                }
            }).thenIdle(DRIVE).thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    yaw[i] = lanes.get(i).vehicle.getYRot();
                    lanes.get(i).vehicle.controlUnit.left = true;
                }
            }).thenIdle(20);
            seq = belts(seq, lanes, problems);
            return seq.thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    WheeledVehicle v = (WheeledVehicle) lane.vehicle;
                    float turned = Mth.wrapDegrees(v.getYRot() - yaw[i]);
                    if (v.getTurnAngle() >= 0) {
                        problems.add(lane.id + " front wheels not at left lock: " + v.getTurnAngle());
                    }
                    if (turned > -10) {
                        problems.add(lane.id + " turned " + fmt(turned) + " deg in 30 ticks of left lock");
                    }
                    TrackUnit left = track(v, true), right = track(v, false);
                    double inner = left.linkSpeeds()[0], outer = right.linkSpeeds()[0];
                    if (!(inner < outer)) {
                        problems.add(lane.id + " left turn: inner belt " + fmt(inner) + " !< outer " + fmt(outer));
                    }
                    groundedCheck(lane, problems);
                }
            });
        });
    }

    /** Left track destroyed: its road wheels lose the ground, that side sits lower, throttle no longer moves it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void halfTrackDestroyedTrackImmobilisesAndDrags(GameTestHelper helper) {
        run(helper, 2, (seq, lanes, problems) -> {
            Vec3[] at = new Vec3[lanes.size()];
            return seq.thenExecute(() -> {
                for (Lane lane : lanes) {
                    track(lane.vehicle, true).setHealth(0);
                }
            }).thenIdle(40).thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    TrackUnit left = track(lane.vehicle, true), right = track(lane.vehicle, false);
                    if (left.isDetached() || left.contactCount() != 0) {
                        problems.add(lane.id + " destroyed track detached " + left.isDetached() + ", rows " + left.contactCount());
                    }
                    double drop = side(lane.vehicle, right) - side(lane.vehicle, left);
                    if (drop < 0.05) {
                        problems.add(lane.id + " destroyed side not lower: " + fmt(drop));
                    }
                    if (VehicleMobility.of(lane.vehicle).speedScale() != 0) {
                        problems.add(lane.id + " mobility " + VehicleMobility.of(lane.vehicle).speedScale());
                    }
                    at[i] = lane.vehicle.position();
                    lane.vehicle.controlUnit.forward = true;
                }
            }).thenIdle(DRIVE).thenExecute(() -> {
                for (int i = 0; i < lanes.size(); i++) {
                    double moved = lanes.get(i).vehicle.position().subtract(at[i]).horizontalDistance();
                    if (moved > 0.5) {
                        problems.add(lanes.get(i).id + " moved " + fmt(moved) + " on one track");
                    }
                }
            });
        });
    }

    /** Over {@link #WINDOW} ticks: each belt's mean speed == mean along-heading speed of the hull point at that belt. */
    private static GameTestSequence belts(GameTestSequence seq, List<Lane> lanes, List<String> problems) {
        int n = lanes.size();
        Vec3[][] last = new Vec3[n][2];
        double[][] belt = new double[n][2];
        double[][] ground = new double[n][2];
        seq = seq.thenExecute(() -> {
            for (int i = 0; i < n; i++) {
                for (int s = 0; s < 2; s++) {
                    last[i][s] = point(lanes.get(i).vehicle, track(lanes.get(i).vehicle, s == 0));
                }
            }
        });
        for (int t = 0; t < WINDOW; t++) {
            seq = seq.thenIdle(1).thenExecute(() -> {
                for (int i = 0; i < n; i++) {
                    AbstractVehicle v = lanes.get(i).vehicle;
                    Vec3 heading = new Vec3(v.getLookAngle().x, 0, v.getLookAngle().z).normalize();
                    for (int s = 0; s < 2; s++) {
                        TrackUnit track = track(v, s == 0);
                        Vec3 now = point(v, track);
                        ground[i][s] += now.subtract(last[i][s]).dot(heading) * 20.0 / WINDOW;
                        belt[i][s] += track.linkSpeeds()[0] / WINDOW;
                        last[i][s] = now;
                    }
                }
            });
        }
        return seq.thenExecute(() -> {
            for (int i = 0; i < n; i++) {
                for (int s = 0; s < 2; s++) {
                    String row = String.format(Locale.ROOT, "%s %s belt %.2f b/s, ground %.2f b/s", lanes.get(i).id,
                            s == 0 ? "left" : "right", belt[i][s], ground[i][s]);
                    CombinedAssault.LOGGER.info("[halftrack] {}", row);
                    if (Math.abs(belt[i][s] - ground[i][s]) > 0.05 * Math.abs(ground[i][s]) + 0.1) {
                        problems.add(row);
                    }
                }
            }
        });
    }

    /** Hull point under track loop 0's centroid x, world. */
    private static Vec3 point(AbstractVehicle v, TrackUnit track) {
        return v.relativeRotPos(v.position().add(v.centerOffset).add(track.lateralOffset(0), 0, 0), false);
    }

    /** Hull height at the track's side, world y. */
    private static double side(AbstractVehicle v, TrackUnit track) {
        return point(v, track).y;
    }

    private static void groundedCheck(Lane lane, List<String> problems) {
        for (SuspensionUnit<?> unit : lane.vehicle.getSuspensionUnits()) {
            int want = unit instanceof TrackUnit t ? t.getData().getRoadWheels().size() : 1;
            if (unit.contactCount() < want) {
                problems.add(lane.id + " " + unit.getId() + " rows " + unit.contactCount() + " < " + want);
            }
        }
    }

    private static TrackUnit track(AbstractVehicle v, boolean left) {
        for (SuspensionUnit<?> unit : v.getSuspensionUnits()) {
            if (unit instanceof TrackUnit t && t.lateralOffset(0) > 0 == left) {
                return t;
            }
        }
        throw new IllegalStateException(v.getDisplayId() + " has no " + (left ? "left" : "right") + " track");
    }

    private static boolean halfTrack(BaseVehicleData<?> data) {
        return data instanceof WheeledVehicleData && data.getParts().stream().anyMatch(p -> p.data() instanceof TrackUnitData);
    }

    private static void run(GameTestHelper helper, int test, Body body) {
        List<ResourceLocation> ids = CommonAssetsManager.vehicleDataManager().getVehicleData().entrySet().stream()
                .filter(e -> halfTrack(e.getValue())).map(e -> e.getKey()).sorted().toList();
        if (ids.isEmpty()) {
            CombinedAssault.LOGGER.warn("[halftrack] no half-track loaded; vacuous");
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        List<Lane> lanes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int y = helper.absolutePos(BlockPos.ZERO).getY() - 1;
        for (int i = 0; i < ids.size(); i++) {
            BlockPos start = new BlockPos(LANE_X - (test * ids.size() + i) * LANE_PITCH, y, 0);
            lane(level, start, true);
            Vec3 spawn = Vec3.atBottomCenterOf(start).add(0, 1.6, 20);
            AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(ids.get(i)).orElseThrow()
                    .construct(level, spawn, 0, 0);
            v.setDisplayId(ids.get(i));
            level.addFreshEntity(v);
            ArmorStand driver = new ArmorStand(level, v.getX(), v.getY(), v.getZ());
            driver.setNoGravity(true);
            level.addFreshEntity(driver);
            lanes.add(new Lane(ids.get(i), start, spawn, v, driver));
        }
        GameTestSequence seq = helper.startSequence().thenIdle(SETTLE).thenExecute(() -> {
            for (Lane lane : lanes) {
                lane.vehicle.setEnergy(lane.vehicle.energyInfo.energyCapacity);
                lane.vehicle.controlUnit.setOperator(lane.driver);
                lane.vehicle.toggleEngine(true);
            }
        });
        body.run(seq, lanes, problems).thenExecute(() -> {
            for (Lane lane : lanes) {
                lane.driver.discard();
                lane.vehicle.discard();
                lane(level, lane.start, false);
            }
            if (!problems.isEmpty()) {
                helper.fail(ids.size() + " half-tracks: " + String.join("; ", problems));
            }
        }).thenSucceed();
    }

    private static void lane(ServerLevel level, BlockPos start, boolean place) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - 4) >> 4; cz <= (start.getZ() + LENGTH) >> 4; cz++) {
                level.setChunkForced(cx, cz, place);
            }
        }
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -4; z <= LENGTH; z++) {
                level.setBlock(start.offset(x, 0, z), (place ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
            }
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
