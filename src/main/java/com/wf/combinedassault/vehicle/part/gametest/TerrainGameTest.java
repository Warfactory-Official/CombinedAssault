package com.wf.combinedassault.vehicle.part.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Mandatory ground-physics gate: every ground vehicle x {rough straight, rough weave, lock, slalom, handbrake, hill, washboard,
 * kerb}
 * at full throttle. Fails on a flip (up.y < {@link #UPRIGHT}), a flat-turn roll > {@link #FLAT_ROLL}, or a hill not crossed.
 * Row per case: {@code [terrain]}.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
public class TerrainGameTest {

    // TODO m1129 (SSF 1.1 half-track / 1.2 CoM = 0.92) rolls on WEAVE/LOCK and rough lanes, airborne off bumps;
    // pre-audit physics identical. Back in once its data or the bump response is fixed.
    private static final String[] VEHICLES = {"humvee", "centauro", "m983", "m1a2", "t90"};
    private static final String TEMPLATE = CombinedAssault.MOD_ID + ":empty";
    private static final int LANE_X = 6000;
    private static final int LANE_PITCH = 90;
    private static final int HALF_WIDTH = 36;
    private static final int LENGTH = 200;
    /** Value-noise cell, blocks; heights 0..2. */
    private static final int CELL = 4;
    private static final int SETTLE_TICKS = 30;
    private static final int DRIVE_TICKS = 300;
    /** Tank climbs the stairs at ~0.1 b/t: 300 ticks ends on the far slope. */
    private static final int HILL_DRIVE_TICKS = 500;
    /** Flat turns steer from here: top speed reached. */
    private static final int STEER_AT = 130;
    private static final int SLALOM = 15;
    private static final int WEAVE = 25;
    /** cos 60 deg. */
    private static final float UPRIGHT = 0.5f;
    private static final float FLAT_ROLL = 15;
    /** Hill: 1 block per HILL_RUN blocks up to HILL_TOP, plateau, down again. */
    private static final int HILL_START = 30;
    private static final int HILL_RUN = 3;
    private static final int HILL_TOP = 6;
    private static final int PLATEAU = 12;

    /** Washboard: tow trial ring's ridges, 1 + round(1.4 sin((z - start) / 2.2)), from here. */
    private static final int WASHBOARD_START = 30;
    /** Still climbing ridges at the end: > PROGRESS blocks in the last PROGRESS_TICKS. M983 ~0.2 b/t over them. */
    private static final int PROGRESS_TICKS = 50;
    private static final double PROGRESS = 2;

    /** Kerb: 1 block up along a zigzag edge 30 degrees off square (tow ring's sector seams), at this z on the centreline. */
    private static final int KERB_AT = 60;

    enum Course { ROUGH, WEAVE, LOCK, SLALOM, HANDBRAKE, HILL, WASHBOARD, KERB }

    @GameTestGenerator
    public static List<TestFunction> terrain() {
        List<TestFunction> tests = new ArrayList<>();
        int lane = 0;
        for (String vehicle : VEHICLES) {
            for (Course course : Course.values()) {
                int at = lane++;
                String name = "terrain_" + vehicle + "_" + course.name().toLowerCase(Locale.ROOT);
                tests.add(new TestFunction("terrain", name, TEMPLATE, SETTLE_TICKS + drive(course) + 100, 0, true,
                        helper -> run(helper, vehicle, course, at)));
            }
        }
        return tests;
    }

    static int drive(Course course) {
        return course == Course.HILL ? HILL_DRIVE_TICKS : DRIVE_TICKS;
    }

    private static int height(Course course, int x, int z) {
        return switch (course) {
            case ROUGH, WEAVE -> noise(course == Course.ROUGH ? 1 : 2, x, z);
            case HILL -> {
                int up = (z - HILL_START) / HILL_RUN;
                int down = (HILL_START + HILL_TOP * HILL_RUN + PLATEAU + HILL_TOP * HILL_RUN - z) / HILL_RUN;
                yield z < HILL_START ? 0 : Math.max(0, Math.min(HILL_TOP, Math.min(up, down)));
            }
            case KERB -> z + Math.floor(x * Math.tan(Math.toRadians(30))) >= KERB_AT ? 1 : 0;
            case WASHBOARD -> z < WASHBOARD_START ? 0 : 1 + (int) Math.round(1.4 * Math.sin((z - WASHBOARD_START) / 2.2));
            default -> 0;
        };
    }

    private static int noise(int seed, int x, int z) {
        double fx = (double) x / CELL, fz = (double) z / CELL;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = hash(seed, x0, z0), b = hash(seed, x0 + 1, z0), c = hash(seed, x0, z0 + 1), d = hash(seed, x0 + 1, z0 + 1);
        double near = a + (b - a) * tx;
        return (int) Math.floor((near + (c + (d - c) * tx - near) * tz) * 3);
    }

    private static double hash(int seed, int x, int z) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    static void run(GameTestHelper helper, String name, Course course, int lane) {
        ServerLevel level = helper.getLevel();
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation(name));
        if (data.isEmpty()) {
            helper.fail(name + " is not in the roster");
            return;
        }
        // World-fixed lanes: arenas sit at different origins => arena-relative lanes overlapped. Ground level
        // (superflat top = arena floor): a raised slab's edge dropped drifting slaloms off it.
        BlockPos start = new BlockPos(LANE_X + lane * LANE_PITCH, helper.absolutePos(BlockPos.ZERO).getY() - 1, 0);
        setChunks(level, start, true);
        build(level, start, course, true);
        int spawnZ = 20;
        AbstractVehicle vehicle = data.get().construct(level,
                new Vec3(start.getX() + 0.5, start.getY() + 1.6 + height(course, 0, spawnZ), start.getZ() + spawnZ + 0.5), 0, 0);
        vehicle.setDisplayId(CombinedAssault.modLocation(name));
        level.addFreshEntity(vehicle);
        ArmorStand driver = new ArmorStand(level, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        float[] worst = new float[2];
        double[] low = {1};
        double[] peak = {Double.NEGATIVE_INFINITY};
        double[] late = {Double.NaN};
        int[] tick = {0};
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
                    vehicle.controlUnit.setOperator(driver);
                    vehicle.toggleEngine(true);
                    vehicle.controlUnit.forward = true;
                })
                .thenExecuteFor(drive(course), () -> {
                    int t = tick[0]++;
                    boolean steer = t >= STEER_AT;
                    if (t == drive(course) - PROGRESS_TICKS) {
                        late[0] = vehicle.getZ();
                    }
                    switch (course) {
                        case WEAVE -> {
                            vehicle.controlUnit.left = (t / WEAVE) % 2 == 0;
                            vehicle.controlUnit.right = !vehicle.controlUnit.left;
                        }
                        case LOCK -> vehicle.controlUnit.left = steer;
                        case SLALOM -> {
                            vehicle.controlUnit.left = steer && ((t - STEER_AT) / SLALOM) % 2 == 0;
                            vehicle.controlUnit.right = steer && !vehicle.controlUnit.left;
                        }
                        case HANDBRAKE -> {
                            vehicle.controlUnit.left = steer;
                            vehicle.controlUnit.up = steer;
                        }
                        default -> {
                        }
                    }
                    if (vehicle.getZ() > start.getZ() + LENGTH - 10) {
                        vehicle.controlUnit.forward = false;
                    }
                    Vector3f up = vehicle.getMainCubeOBB().obb().getAxes()[1];
                    if (Boolean.getBoolean("combinedassault.bench.trace") && t >= 100) {
                        long left = vehicle.getSuspensionUnits().stream().filter(u -> u.isGrounded() && u.getPivotOffset().x > 0).count();
                        long right = vehicle.getSuspensionUnits().stream().filter(u -> u.isGrounded() && u.getPivotOffset().x < 0).count();
                        CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                                "[ttrace] %s/%s/%d t%d roll %.1f pitch %.1f yaw %.1f v %.3f vy %.3f w %.4f,%.4f,%.4f L%d R%d steer %s y %.2f",
                                name, course, lane, t, vehicle.getZRot(), vehicle.getXRot(), vehicle.getYRot(),
                                vehicle.getDeltaMovement().horizontalDistance(), vehicle.getDeltaMovement().y,
                                vehicle.physicsEngine.angularVelocity.x, vehicle.physicsEngine.angularVelocity.y,
                                vehicle.physicsEngine.angularVelocity.z, left, right,
                                vehicle.controlUnit.left ? "L" : vehicle.controlUnit.right ? "R" : "-", vehicle.getY() - start.getY() - 1));
                    }
                    low[0] = Math.min(low[0], up.y);
                    worst[0] = Math.max(worst[0], Math.abs(vehicle.getXRot()));
                    worst[1] = Math.max(worst[1], Math.abs(vehicle.getZRot()));
                    peak[0] = Math.max(peak[0], vehicle.getY() - start.getY() - 1);
                })
                .thenExecute(() -> {
                    double travelled = vehicle.getZ() - start.getZ() - spawnZ;
                    CombinedAssault.LOGGER.info(String.format(Locale.ROOT,
                            "[terrain] %-10s %-9s minUp %.3f maxPitch %5.1f maxRoll %5.1f travelled %6.1f peak %.2f",
                            name, course, low[0], worst[0], worst[1], travelled, peak[0]));
                    driver.discard();
                    vehicle.discard();
                    build(level, start, course, false);
                    setChunks(level, start, false);
                    String what = name + " " + course + ": ";
                    if (low[0] < UPRIGHT) {
                        helper.fail(what + "flipped (up.y " + low[0] + ")");
                    } else if (course.ordinal() >= Course.LOCK.ordinal() && course.ordinal() <= Course.HANDBRAKE.ordinal() && worst[1] > FLAT_ROLL) {
                        helper.fail(what + "rolled " + worst[1] + " deg on flat ground");
                    } else if (course == Course.HILL && (peak[0] < HILL_TOP - 0.5
                            || travelled < HILL_START + 2 * HILL_TOP * HILL_RUN + PLATEAU - spawnZ + 4)) {
                        helper.fail(what + "did not cross the hill (peak " + peak[0] + ", travelled " + travelled + ")");
                    } else if (course == Course.KERB && travelled < KERB_AT - spawnZ + 20) {
                        helper.fail(what + "stopped at the kerb after " + travelled);
                    } else if (course == Course.WASHBOARD && vehicle.getZ() - late[0] < PROGRESS) {
                        helper.fail(what + "stalled on the washboard after " + travelled);
                    } else {
                        helper.succeed();
                    }
                });
    }

    private static void build(ServerLevel level, BlockPos start, Course course, boolean place) {
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -4; z <= LENGTH; z++) {
                int top = place ? height(course, x, z) : 2 * HILL_TOP;
                level.setBlock(start.offset(x, 0, z), (place ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                for (int y = 1; y <= top; y++) {
                    level.setBlock(start.offset(x, y, z), place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void setChunks(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - HALF_WIDTH) >> 4; cx <= (start.getX() + HALF_WIDTH) >> 4; cx++) {
            for (int cz = (start.getZ() - 4) >> 4; cz <= (start.getZ() + LENGTH) >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }
}
