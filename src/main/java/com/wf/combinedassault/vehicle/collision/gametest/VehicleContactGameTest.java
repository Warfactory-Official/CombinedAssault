package com.wf.combinedassault.vehicle.collision.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;
import com.wf.combinedassault.vehicle.collision.BoxBuffer;
import com.wf.combinedassault.vehicle.collision.HullContact;
import com.wf.combinedassault.vehicle.collision.HullPieces;
import com.wf.combinedassault.vehicle.collision.SectionCollision;
import com.wf.combinedassault.vehicle.collision.SweptHull;
import com.wf.wflib.util.OBB;

import java.util.Locale;
import java.util.Optional;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleContactGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRUCK = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation KRUPP = CombinedAssault.modLocation("krupp_c34");
    private static final ResourceLocation TRUCK_TALL = CombinedAssault.modLocation("m983");
    /** Truck bearing from the Krupp; gun starts at 0. */
    private static final double BEARING = Math.toRadians(60);
    /** Lanes clear of the arena grid and of SuspensionGameTest's +z lanes. */
    private static final int LANE_X = 600;
    private static final int LANE_LENGTH = 40;

    /** Three blocks a tick is most of a humvee: the end pose alone would put it past the other one. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aFastHullDoesNotPassThroughAnother(GameTestHelper helper) {
        BlockPos start = lane(helper, 0);
        double floor = start.getY() + 1;
        AbstractVehicle target = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 0.2, start.getZ() + 20.5));
        AbstractVehicle bullet = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 0.2, start.getZ() + 4.5));
        if (target == null || bullet == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> bullet.setDeltaMovement(0, 0, 3))
                .thenIdle(20)
                .thenExecute(() -> {
                    double bulletZ = bullet.getZ();
                    double targetZ = target.getZ();
                    release(helper, start, bullet, target);
                    if (bulletZ >= targetZ) {
                        helper.fail(String.format(Locale.ROOT, "a hull at 3 b/t ended at z %.2f, past the one at %.2f",
                                bulletZ, targetZ));
                    }
                })
                .thenSucceed();
    }

    /** Dropped onto another hull it stays on top: the response is 3D, not a horizontal nudge. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aHullLandingOnAnotherStaysOnTop(GameTestHelper helper) {
        BlockPos start = lane(helper, 1);
        double floor = start.getY() + 1;
        AbstractVehicle below = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 0.2, start.getZ() + 20.5));
        AbstractVehicle above = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 6, start.getZ() + 20.5));
        if (below == null || above == null) {
            return;
        }
        float[] worst = new float[1];
        helper.startSequence()
                .thenIdle(80)
                .thenExecuteFor(20, () -> {
                    float depth = OBB.mtv(above.getMainCubeOBB().obb(), 0, 0, 0, below.getMainCubeOBB().obb(), new Vector3f());
                    worst[0] = Math.max(worst[0], depth);
                })
                .thenExecute(() -> {
                    boolean onTop = above.getY() > below.getY() + 0.5;
                    release(helper, start, above, below);
                    if (!onTop) {
                        helper.fail("the upper hull ended level with or under the lower one");
                    } else if (worst[0] > 0.15) {
                        helper.fail(String.format(Locale.ROOT, "at rest the upper hull is %.2f into the lower one", worst[0]));
                    }
                })
                .thenSucceed();
    }

    /** Krupp gun traversed into a parked truck stops at it; neither is lifted (was: Krupp launched 6.7 b/t). */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aGunTraversedIntoAHullStopsAtIt(GameTestHelper helper) {
        AbstractVehicle[] truck = new AbstractVehicle[1];
        double[] worst = new double[2];
        TestLanes.run(helper, -2000, 12, 12, KRUPP, (seq, krupp) -> seq
                .thenExecute(() -> truck[0] = TestLanes.spawn(helper, TRUCK_TALL,
                        krupp.get().position().add(9.37 * Math.sin(BEARING), 0.2, 9.37 * Math.cos(BEARING))))
                .thenIdle(40)
                .thenExecuteFor(160, () -> {
                    WeaponUnit gun = gun(krupp.get());
                    gun.aimAlong(truck[0].position().add(0, 1, 0).subtract(gun.aimPivot()), 64);
                    for (AbstractVehicle v : new AbstractVehicle[] {krupp.get(), truck[0]}) {
                        worst[0] = Math.max(worst[0], v.getDeltaMovement().length());
                        worst[1] = Math.max(worst[1], v.getY() - krupp.get().blockPosition().getY());
                    }
                })
                .thenExecute(() -> {
                    Vec3 dir = gun(krupp.get()).worldVec();
                    double laid = Math.toDegrees(Math.atan2(dir.x, dir.z));
                    truck[0].discard();
                    helper.assertTrue(laid < Math.toDegrees(BEARING) - 10,
                            String.format(Locale.ROOT, "gun laid to %.1f deg, through the truck at %.1f", laid,
                                    Math.toDegrees(BEARING)));
                    helper.assertTrue(worst[0] < 0.05 && worst[1] < 0.5, String.format(Locale.ROOT,
                            "contact moved a hull: speed %.3f b/t, rise %.2f", worst[0], worst[1]));
                }), at -> { });
    }

    private static WeaponUnit gun(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit) {
                return unit;
            }
        }
        throw new IllegalStateException(vehicle + " has no gun");
    }

    /** A block change marks its section stale; until the next prepare the old snapshot is served, never air. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aChangedSectionStaysReadableUntilRebuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        ChunkCollisionCache cache = ChunkCollisionCache.of(level);
        AABB around = new AABB(pos).inflate(1);
        cache.prepare(level, around);
        long key = SectionPos.asLong(pos);
        SectionCollision before = cache.snapshotAt(key);
        level.setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
        SectionCollision stale = cache.snapshotAt(key);
        cache.prepare(level, around);
        SectionCollision rebuilt = cache.snapshotAt(key);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        if (before == null || before.isEmpty()) {
            helper.fail("the prepared section holds no stone, so this proves nothing");
        } else if (stale != before) {
            helper.fail("a block change dropped the section instead of keeping it readable: " + stale);
        } else if (rebuilt == before) {
            helper.fail("the next prepare did not rebuild the changed section");
        } else {
            helper.succeed();
        }
    }

    /** sweepBy (the tow's move) stops against a wall instead of setting the hull inside it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aConstraintMoveStopsAtAWall(GameTestHelper helper) {
        BlockPos start = lane(helper, 2);
        double floor = start.getY() + 1;
        int wallZ = start.getZ() + 12;
        for (int x = -3; x <= 3; x++) {
            for (int y = 1; y <= 4; y++) {
                helper.getLevel().setBlockAndUpdate(new BlockPos(start.getX() + x, start.getY() + y, wallZ),
                        Blocks.STONE.defaultBlockState());
            }
        }
        AbstractVehicle truck = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 0.2, start.getZ() + 4.5));
        if (truck == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(30)
                .thenExecute(() -> {
                    truck.updateOBBs();
                    double taken = truck.sweepBy(0, 0, 10);
                    truck.updateOBBs();
                    OBB hull = truck.getMainCubeOBB().obb();
                    double front = hull.centerZ() + hull.extents().z;
                    release(helper, start, truck);
                    for (int x = -3; x <= 3; x++) {
                        for (int y = 1; y <= 4; y++) {
                            helper.getLevel().setBlockAndUpdate(new BlockPos(start.getX() + x, start.getY() + y, wallZ),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                    if (taken >= 1) {
                        helper.fail("a 10 block move through a wall was taken whole");
                    } else if (front > wallZ + 0.01) {
                        helper.fail(String.format(Locale.ROOT, "the hull's front is at %.3f, inside the wall at %d", front, wallZ));
                    }
                })
                .thenSucceed();
    }

    /** Side-on into the rear quarter: the struck hull turns (r x J), not just slides. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aTankRammingATrucksRearQuarterSpinsIt(GameTestHelper helper) {
        BlockPos start = lane(helper, 5);
        double floor = start.getY() + 1;
        ServerLevel level = helper.getLevel();
        wideFloor(level, start, true);
        // Truck along +z, centre at lane z 26; tank 10 blocks to its -x side, aimed (+x) 2.5 blocks behind its centre.
        AbstractVehicle truck = spawn(helper, TRUCK, new Vec3(start.getX() + 3.5, floor + 0.2, start.getZ() + 26.5));
        AbstractVehicle tank = spawn(helper, TANK, new Vec3(start.getX() - 9.5, floor + 0.2, start.getZ() + 24.0));
        if (truck == null || tank == null) {
            return;
        }
        tank.setYRot(-90);
        ArmorStand driver = new ArmorStand(level, tank.getX(), tank.getY(), tank.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        float[] yaw0 = new float[1];
        double[] closing = new double[1];
        boolean[] hit = new boolean[1];
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    yaw0[0] = truck.getYRot();
                    tank.setEnergy(tank.energyInfo.capacityUnits());
                    tank.controlUnit.setOperator(driver);
                    tank.toggleEngine(true);
                    tank.controlUnit.forward = true;
                })
                .thenExecuteFor(120, () -> {
                    if (!hit[0] && (truck.getDeltaMovement().horizontalDistance() > 0.02
                            || Math.abs(truck.getYRot() - yaw0[0]) > 0.5F)) {
                        hit[0] = true;
                        closing[0] = tank.getDeltaMovement().x;
                        tank.controlUnit.forward = false;
                    }
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    float turned = Math.abs(Mth.wrapDegrees(truck.getYRot() - yaw0[0]));
                    double shoved = truck.getX() - (start.getX() + 3.5);
                    String report = String.format(Locale.ROOT, "m1a2 at %.3f b/t into a humvee's rear quarter: hit %b, truck turned %.1f deg, shoved %.2f",
                            closing[0], hit[0], turned, shoved);
                    CombinedAssault.LOGGER.info("[ram] {}", report);
                    driver.discard();
                    wideFloor(level, start, false);
                    release(helper, start, truck, tank);
                    if (!hit[0] || turned < 10 || shoved < 0.3) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    /** Tank driven into a humvee pinned broadside against a wall: not shoved up it. World-blind island: 3.5-4.1. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aTankCannotShoveAWallPinnedTruckUpTheWall(GameTestHelper helper) {
        // Absolute, near the origin: arena coordinates (5e6..1.4e7) => float rounding; rise 1.2-1.8 at 5e6, 3.1 at 1.27e7.
        BlockPos start = laneAt(helper, new BlockPos(3000, helper.absolutePos(BlockPos.ZERO).getY(), 3000));
        double floor = start.getY() + 1;
        ServerLevel level = helper.getLevel();
        int wallZ = start.getZ() + 24;
        setWall(level, start, wallZ, true);
        AbstractVehicle truck = spawn(helper, TRUCK, new Vec3(start.getX() + 0.5, floor + 0.2, wallZ - 3));
        AbstractVehicle tank = spawn(helper, TANK, new Vec3(start.getX() + 0.5, floor + 0.2, start.getZ() + 8.5));
        if (truck == null || tank == null) {
            return;
        }
        truck.setYRot(90);
        truck.updateOBBs();
        truck.setPos(truck.getX(), truck.getY(), truck.getZ() + wallZ - 0.1 - OBB.toAABB(truck.getOBBs()).maxZ);
        ArmorStand driver = new ArmorStand(level, tank.getX(), tank.getY(), tank.getZ());
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        double[] rise = new double[2];
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    rise[1] = truck.getY();
                    tank.setEnergy(tank.energyInfo.capacityUnits());
                    tank.controlUnit.setOperator(driver);
                    tank.toggleEngine(true);
                    tank.controlUnit.forward = true;
                })
                .thenExecuteFor(160, () -> rise[0] = Math.max(rise[0], truck.getY() - rise[1]))
                .thenExecute(() -> tank.controlUnit.forward = false)
                .thenExecuteFor(60, () -> rise[0] = Math.max(rise[0], truck.getY() - rise[1]))
                .thenExecute(() -> {
                    String report = String.format(Locale.ROOT, "pinned humvee rose %.2f", rise[0]);
                    CombinedAssault.LOGGER.info("[contact] {}", report);
                    driver.discard();
                    setWall(level, start, wallZ, false);
                    release(helper, start, truck, tank);
                    if (rise[0] > 2.5) {
                        helper.fail(report);
                    }
                })
                .thenSucceed();
    }

    private static void setWall(ServerLevel level, BlockPos start, int wallZ, boolean place) {
        for (int x = -4; x <= 4; x++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlockAndUpdate(new BlockPos(start.getX() + x, start.getY() + y, wallZ),
                        place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Hull 0.05 into a wall: along or out free, deeper refused. Keel 0.1 into a block floor: slides free. */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void anEmbeddedHullCannotSinkDeeperIntoAWall(GameTestHelper helper) {
        OBB hull = new OBB(new Vector3f(0, 1, 0), new Vector3f(1, 0.5F, 2), new Quaternionf().rotateY(0.1F));
        BoxBuffer wall = new BoxBuffer();
        double face = hull.bounds().maxX - 0.05;
        wall.add(face, 0, -4, face + 1, 3, 4);
        double deeper = SweptHull.timeOfImpact(hull, wall, 0.1, 0, 0, null);
        double out = SweptHull.timeOfImpact(hull, wall, -0.1, 0, 0, null);
        double along = SweptHull.timeOfImpact(hull, wall, 0, 0, 0.1, null);
        OBB keel = new OBB(new Vector3f(0.3F, 0.4F, 0.5F), new Vector3f(6, 0.5F, 1), new Quaternionf());
        BoxBuffer floor = new BoxBuffer();
        for (int x = -8; x < 8; x++) {
            for (int z = -2; z < 2; z++) {
                floor.add(x, -1, z, x + 1, 0, z + 1);
            }
        }
        double slide = SweptHull.timeOfImpact(keel, floor, 0.3, 0, 0, null);
        String report = String.format(Locale.ROOT, "deeper %.3f out %.3f along %.3f keel slide %.3f", deeper, out, along, slide);
        CombinedAssault.LOGGER.info("[contact] {}", report);
        if (deeper > 0 || out < 1 || along < 1 || slide < 1) {
            helper.fail(report);
            return;
        }
        helper.succeed();
    }

    /** {@link HullPieces#overlaps} = {@code OBB.mtv > 0}; boundsMeet never rejects an overlapping pair. */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void hullPiecesAgreeWithObbMtv(GameTestHelper helper) {
        RandomSource random = RandomSource.create(7);
        HullPieces a = new HullPieces(), b = new HullPieces();
        Vector3f axis = new Vector3f();
        int overlapping = 0;
        for (int i = 0; i < 2000; i++) {
            OBB p = randomBox(random), q = randomBox(random);
            double dx = random.nextGaussian() * 0.5, dy = random.nextGaussian() * 0.5, dz = random.nextGaussian() * 0.5;
            a.load(p);
            b.load(q);
            boolean expected = OBB.mtv(p, dx, dy, dz, q, axis) > 0;
            boolean actual = a.overlaps(0, b, 0, dx, dy, dz);
            if (expected != actual || expected && !a.boundsMeet(0, b, 0, dx, dy, dz)) {
                helper.fail("pose " + i + ": mtv says " + expected + ", HullPieces " + actual);
                return;
            }
            overlapping += expected ? 1 : 0;
        }
        if (overlapping < 200 || overlapping > 1800) {
            helper.fail(overlapping + " of 2000 overlapping: poses do not exercise both outcomes");
            return;
        }
        helper.succeed();
    }

    private static OBB randomBox(RandomSource random) {
        Vector3f centre = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian());
        Vector3f extents = new Vector3f(0.2F + random.nextFloat() * 2, 0.2F + random.nextFloat(), 0.2F + random.nextFloat() * 3);
        Quaternionf rotation = new Quaternionf().rotateXYZ(random.nextFloat() * 6.3F, random.nextFloat() * 6.3F, random.nextFloat() * 6.3F);
        return new OBB(centre, extents, rotation);
    }

    /** Free bodies, off-centre hit: sum m dv = 0 and angular momentum about the origin conserved. */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void aContactImpulseConservesMomentum(GameTestHelper helper) {
        HullContact contact = new HullContact();
        contact.reset(0, 0, 0);
        OBB a = new OBB(new Vector3f(0, 0, 0), new Vector3f(1, 0.5F, 2), new Quaternionf());
        OBB b = new OBB(new Vector3f(1.9F, 0.3F, 1.5F), new Vector3f(1, 0.5F, 2), new Quaternionf().rotateY(0.3F));
        HullContact.Body bodyA = body(contact, 2000, a, new Vector3f(0.4F, 0, 0));
        HullContact.Body bodyB = body(contact, 3000, b, new Vector3f());
        contact.pair(0, 1);
        contact.collide(a, b);
        contact.solve();
        Vector3f linear = new Vector3f(bodyA.dv).mul(2000).add(new Vector3f(bodyB.dv).mul(3000));
        Vector3f angular = angularMomentum(bodyA, 2000).add(angularMomentum(bodyB, 3000));
        // Worst normal approach speed at a contact point after the solve (v + w x r).
        float closingAfter = Float.MAX_VALUE;
        for (int i = 0; i < contact.count(); i++) {
            Vector3f p = contact.point(i, new Vector3f());
            Vector3f n = contact.normal(i, new Vector3f());
            Vector3f va = new Vector3f(bodyA.dw).cross(new Vector3f(p).sub(bodyA.com)).add(bodyA.velocity).add(bodyA.dv);
            Vector3f vb = new Vector3f(bodyB.dw).cross(new Vector3f(p).sub(bodyB.com)).add(bodyB.velocity).add(bodyB.dv);
            closingAfter = Math.min(closingAfter, vb.sub(va).dot(n));
        }
        String report = String.format(Locale.ROOT, "%d points; momentum change %s, angular %s; spin A %s B %s; closing after %.3f",
                contact.count(), linear, angular, bodyA.dw, bodyB.dw, closingAfter);
        CombinedAssault.LOGGER.info("[contact] {}", report);
        if (contact.count() == 0 || linear.length() > 1.0e-2F || angular.length() > 5.0e-2F
                || bodyA.dw.length() < 1.0e-4F || closingAfter < -1.0e-3F) {
            helper.fail(report);
            return;
        }
        helper.succeed();
    }

    /** Box resting square on a larger box: four points at the small face's corners, depth each. */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void aFaceContactHasFourCorners(GameTestHelper helper) {
        HullContact contact = new HullContact();
        contact.reset(0, 0, 0);
        OBB below = new OBB(new Vector3f(0, 0, 0), new Vector3f(3, 1, 3), new Quaternionf());
        OBB above = new OBB(new Vector3f(0.5F, 1.9F, -0.5F), new Vector3f(1, 1, 1), new Quaternionf());
        contact.pair(0, 1);
        contact.collide(below, above);
        StringBuilder points = new StringBuilder();
        boolean good = contact.count() == 4;
        Vector3f p = new Vector3f(), n = new Vector3f();
        for (int i = 0; i < contact.count(); i++) {
            contact.point(i, p);
            contact.normal(i, n);
            points.append(String.format(Locale.ROOT, " (%.2f %.2f %.2f d%.2f n%.1f)", p.x, p.y, p.z, contact.depth(i), n.y));
            good &= Math.abs(Math.abs(p.x - 0.5F) - 1) < 1.0e-3F && Math.abs(Math.abs(p.z + 0.5F) - 1) < 1.0e-3F
                    && Math.abs(contact.depth(i) - 0.1F) < 1.0e-3F && n.y > 0.999F;
        }
        if (!good) {
            helper.fail(contact.count() + " points:" + points);
            return;
        }
        helper.succeed();
    }

    /** x -14..8 over the lane: the tank's run-up is outside the standard lane floor. */
    private static void wideFloor(ServerLevel level, BlockPos start, boolean place) {
        for (int cx = (start.getX() - 14) >> 4; cx <= (start.getX() + 8) >> 4; cx++) {
            for (int cz = start.getZ() >> 4; cz <= (start.getZ() + LANE_LENGTH) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, place);
            }
        }
        for (int x = -14; x <= 8; x++) {
            for (int z = 0; z <= LANE_LENGTH; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), place ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static HullContact.Body body(HullContact contact, float mass, OBB box, Vector3f velocity) {
        HullContact.Body body = contact.addBody();
        body.inverseMass = 1 / mass;
        body.com.set((float) box.centerX(), (float) box.centerY(), (float) box.centerZ());
        body.velocity.set(velocity);
        Vector3f e = box.extents();
        float w = 2 * e.x, h = 2 * e.y, d = 2 * e.z;
        Matrix3f inverse = new Matrix3f().scaling(12 / (mass * (h * h + d * d)), 12 / (mass * (w * w + d * d)), 12 / (mass * (w * w + h * h)));
        Matrix3f r = new Matrix3f().set(box.rotation());
        body.inverseInertia.set(r).mul(inverse).mul(r.transpose());
        return body;
    }

    /** com x m dv + I dw. */
    private static Vector3f angularMomentum(HullContact.Body body, float mass) {
        Vector3f orbital = new Vector3f(body.com).cross(new Vector3f(body.dv).mul(mass));
        Vector3f spin = new Matrix3f(body.inverseInertia).invert().transform(new Vector3f(body.dw));
        return orbital.add(spin);
    }

    private static BlockPos lane(GameTestHelper helper, int index) {
        return laneAt(helper, helper.absolutePos(BlockPos.ZERO).offset(LANE_X + index * 16, 0, 0));
    }

    private static BlockPos laneAt(GameTestHelper helper, BlockPos start) {
        ServerLevel level = helper.getLevel();
        forceLane(level, start, true);
        for (int x = -4; x <= 4; x++) {
            for (int z = 0; z <= LANE_LENGTH; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        return start;
    }

    private static void release(GameTestHelper helper, BlockPos start, AbstractVehicle... vehicles) {
        for (AbstractVehicle vehicle : vehicles) {
            vehicle.discard();
        }
        ServerLevel level = helper.getLevel();
        for (int x = -4; x <= 4; x++) {
            for (int z = 0; z <= LANE_LENGTH; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.AIR.defaultBlockState());
            }
        }
        forceLane(level, start, false);
    }

    private static void forceLane(ServerLevel level, BlockPos start, boolean forced) {
        for (int cx = (start.getX() - 4) >> 4; cx <= (start.getX() + 4) >> 4; cx++) {
            for (int cz = start.getZ() >> 4; cz <= (start.getZ() + LANE_LENGTH) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id, Vec3 at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle " + id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }

}
