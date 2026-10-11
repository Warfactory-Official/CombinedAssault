package com.wf.combinedassault.vehicle.parenting.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.item.VehicleSpawnItem;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.parenting.VehicleMounts;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;
import com.wf.combinedassault.vehicle.pojo.MountableInfo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Mount (emplacement) != park: park releases on engine start, controls taken, or hit; mount must survive all three.
 * No pack declares a mount point yet; points here are built in code.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleMountGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation CARRIER = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation MOUNTED = CombinedAssault.modLocation("m252a1");

    private static MountPointInfo bed() {
        MountPointInfo point = new MountPointInfo();
        point.id = "bed";
        point.offset = new Vec3(0, 2, -1.5);
        point.yaw = 90;
        return point;
    }

    /** Moving the carrier re-poses the mount at the same offset and yaw. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aMountedVehicleIsCarried(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle mounted = spawn(helper, MOUNTED, 6);
        if (carrier == null || mounted == null) {
            return;
        }
        MountPointInfo point = bed();
        carrier.updateOBBs();
        if (!VehicleHarness.mount(mounted, carrier, point)) {
            helper.fail("mounting was refused; check that the carrier has published a deck snapshot");
            return;
        }
        Vec3 expected = VehicleMounts.worldPositionOf(carrier, point);
        if (mounted.position().distanceTo(expected) > 1.0E-4) {
            helper.fail("mounting should have put it at " + expected + ", it is at " + mounted.position());
            return;
        }

        // applyChildren by hand: isolates the frame arithmetic from tick-order between two entities.
        carrier.setPos(carrier.getX() + 12, carrier.getY(), carrier.getZ() - 5);
        carrier.setYRot(carrier.getYRot() + 37);
        carrier.updateOBBs();
        VehicleHarness.applyChildren(carrier);

        Vec3 moved = VehicleMounts.worldPositionOf(carrier, point);
        if (mounted.position().distanceTo(moved) > 1.0E-3) {
            helper.fail("after the carrier moved, the mount should be at " + moved
                    + ", it is at " + mounted.position());
            return;
        }
        float expectedYaw = carrier.getYRot() + point.yaw;
        if (Math.abs(net.minecraft.util.Mth.degreesDifference(mounted.getYRot(), expectedYaw)) > 0.1f) {
            helper.fail("the mount should face " + expectedYaw + ", faces " + mounted.getYRot());
            return;
        }
        discard(carrier, mounted);
        helper.succeed();
    }

    /** Engine start, power, and a hit each release a park; none may release an emplacement. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void nothingShakesAnEmplacementLoose(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle mounted = spawn(helper, MOUNTED, 6);
        if (carrier == null || mounted == null) {
            return;
        }
        carrier.updateOBBs();
        if (!VehicleHarness.mount(mounted, carrier, bed())) {
            helper.fail("mounting was refused");
            return;
        }
        mounted.toggleEngine(true);
        VehicleHarness.tick(mounted);
        if (!VehicleHarness.isMounted(mounted)) {
            helper.fail("starting the engine released the mount");
            return;
        }
        mounted.setPower(1f);
        VehicleHarness.tick(mounted);
        if (!VehicleHarness.isMounted(mounted)) {
            helper.fail("applying power released the mount");
            return;
        }
        // wake() releases a park on purpose, for knockback; must not release a mount.
        VehicleHarness.wake(mounted);
        if (!VehicleHarness.isMounted(mounted)) {
            helper.fail("a hit released the mount");
            return;
        }
        if (!VehicleMounts.unmount(mounted)) {
            helper.fail("an explicit unmount should release it");
            return;
        }
        if (VehicleHarness.isHarnessed(mounted)) {
            helper.fail("after unmounting it should be back on its own physics");
            return;
        }
        discard(carrier, mounted);
        helper.succeed();
    }

    /** Power housekeeping MUST run though harness skips physics (regression: mounted guns got no power). */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void anEmplacementBuildsPowerWithItsEngineRunning(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle mounted = spawn(helper, MOUNTED, 6);
        if (carrier == null || mounted == null) {
            return;
        }
        carrier.updateOBBs();
        if (!VehicleHarness.mount(mounted, carrier, bed())) {
            helper.fail("mounting was refused");
            return;
        }
        mounted.addEnergy(1f);
        mounted.toggleEngine(true);
        float startingPower = mounted.getPower();

        helper.runAfterDelay(60, () -> {
            if (!VehicleHarness.isMounted(mounted)) {
                helper.fail("running the engine released the mount");
                return;
            }
            if (mounted.getPower() <= startingPower) {
                helper.fail("a mounted vehicle with its engine on built no power (" + startingPower
                        + " -> " + mounted.getPower() + "), so its weapons can never traverse");
                return;
            }
            if (!mounted.hasPower()) {
                helper.fail("after 60 ticks a running mount should have power, has " + mounted.getPower());
                return;
            }
            discard(carrier, mounted);
            helper.succeed();
        });
    }

    /** A mounts B mounts A would pose each from the other for ever; must be refused. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void mountingRefusesACycle(GameTestHelper helper) {
        AbstractVehicle first = spawn(helper, CARRIER, 2);
        AbstractVehicle second = spawn(helper, MOUNTED, 6);
        if (first == null || second == null) {
            return;
        }
        first.updateOBBs();
        second.updateOBBs();
        if (!VehicleHarness.mount(second, first, bed())) {
            helper.fail("the first mount was refused");
            return;
        }
        if (VehicleHarness.mount(first, second, bed())) {
            helper.fail("mounting the carrier onto its own passenger should be refused");
            return;
        }
        discard(first, second);
        helper.succeed();
    }

    /** Point accepts only its declared types, and only while free. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void pointsFilterAndFillUp(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle mounted = spawn(helper, MOUNTED, 6);
        if (carrier == null || mounted == null) {
            return;
        }
        MountPointInfo point = bed();
        point.accepts = List.of("#mortar");
        point.maxMass = 10;
        carrier.mountPoints = List.of(point);
        carrier.updateOBBs();

        FakePlayer player = aiming(helper, carrier, point);
        if (VehicleMounts.aimedPoint(carrier, player, MOUNTED.toString(), List.of(), 1f) != null) {
            helper.fail("a point that only accepts #mortar took something that is not one");
            return;
        }
        if (VehicleMounts.aimedPoint(carrier, player, MOUNTED.toString(), List.of("mortar"), 50f) != null) {
            helper.fail("a point with max_mass 10 took a vehicle of mass 50");
            return;
        }
        if (VehicleMounts.aimedPoint(carrier, player, MOUNTED.toString(), List.of("mortar"), 1f) == null) {
            helper.fail("a point should take a matching, light enough vehicle the player is aiming at");
            return;
        }
        if (!VehicleHarness.mount(mounted, carrier, point)) {
            helper.fail("mounting was refused");
            return;
        }
        if (!VehicleMounts.isOccupied(carrier, point.id)
                || VehicleMounts.occupantOf(carrier, point.id) != mounted) {
            helper.fail("the point should report itself occupied by the vehicle on it");
            return;
        }
        if (VehicleMounts.aimedPoint(carrier, player, MOUNTED.toString(), List.of("mortar"), 1f) != null) {
            helper.fail("an occupied point should not be offered again");
            return;
        }
        discard(carrier, mounted);
        player.discard();
        helper.succeed();
    }

    /** Item-placement path: the gesture a player actually uses. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void placingFromAnItemMountsIt(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        if (carrier == null) {
            return;
        }
        Optional<BaseVehicleData> mountedData = CommonAssetsManager.vehicleDataManager().getVehicleData(MOUNTED);
        if (mountedData.isEmpty()) {
            helper.fail("the mountable test vehicle is not in the roster");
            return;
        }
        MountPointInfo point = bed();
        carrier.mountPoints = List.of(point);
        carrier.updateOBBs();
        // Simulates pack JSON "mountable": {"enabled": true}.
        MountableInfo mountable = mountedData.get().getMountable();
        boolean wasEnabled = mountable.enabled;
        mountable.enabled = true;
        try {
            FakePlayer player = aiming(helper, carrier, point);
            ItemStack stack = ((VehicleSpawnItem) AllItems.VEHICLE_SPAWN_ITEM.get()).createInstance(MOUNTED);
            if (!VehicleMounts.place(carrier, player, stack)) {
                helper.fail("placing a mountable vehicle on an aimed point should consume the click");
                return;
            }
            AbstractVehicle placed = VehicleMounts.occupantOf(carrier, point.id);
            if (placed == null) {
                helper.fail("nothing ended up on the point");
                return;
            }
            if (!VehicleHarness.isMounted(placed)) {
                helper.fail("the placed vehicle is on the point but not mounted to it");
                return;
            }
            if (!stack.isEmpty()) {
                helper.fail("placing should have consumed the item, " + stack.getCount() + " left");
                return;
            }
            discard(carrier, placed);
            player.discard();
            helper.succeed();
        } finally {
            mountable.enabled = wasEnabled;
        }
    }

    /** Mount survives a save/reload round trip. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aMountSurvivesASaveAndReload(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle mounted = spawn(helper, MOUNTED, 6);
        if (carrier == null || mounted == null) {
            return;
        }
        MountPointInfo point = bed();
        carrier.mountPoints = List.of(point);
        carrier.updateOBBs();
        if (!VehicleHarness.mount(mounted, carrier, point)) {
            helper.fail("mounting was refused");
            return;
        }
        CompoundTag saved = new CompoundTag();
        mounted.addAdditionalSaveData(saved);
        if (!saved.hasUUID("MountCarrier")) {
            helper.fail("a mounted vehicle must write down which carrier it is on");
            return;
        }
        VehicleMounts.unmount(mounted);
        mounted.discard();

        AbstractVehicle reloaded = spawn(helper, MOUNTED, 6);
        if (reloaded == null) {
            return;
        }
        VehicleMounts.load(reloaded, saved);
        if (reloaded.pendingMount() == null) {
            helper.fail("reading the save data should leave a mount waiting for its carrier");
            return;
        }
        helper.runAfterDelay(30, () -> {
            if (!VehicleHarness.isMounted(reloaded)) {
                helper.fail("the saved mount never found its carrier again");
                return;
            }
            if (VehicleHarness.carrierOf(reloaded) != carrier) {
                helper.fail("it relinked to the wrong carrier");
                return;
            }
            if (reloaded.pendingMount() != null) {
                helper.fail("the pending link should be cleared once it is mounted");
                return;
            }
            discard(carrier, reloaded);
            helper.succeed();
        });
    }

    /** Carrier's chunk unloads first: the mount waits in place, and remounts when the carrier returns. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aCarrierUnloadingFirstLeavesTheMountWaiting(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle mounted = spawn(helper, MOUNTED, 6);
        if (carrier == null || mounted == null) {
            return;
        }
        MountPointInfo point = bed();
        carrier.mountPoints = List.of(point);
        carrier.updateOBBs();
        if (!VehicleHarness.mount(mounted, carrier, point)) {
            helper.fail("mounting was refused");
            return;
        }
        CompoundTag carrierSave = new CompoundTag();
        carrier.save(carrierSave);
        carrier.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        if (VehicleHarness.isHarnessed(mounted) || mounted.pendingMount() == null) {
            helper.fail("the carrier unloading should leave the mount waiting, not released");
            return;
        }
        CompoundTag mountedSave = new CompoundTag();
        mounted.addAdditionalSaveData(mountedSave);
        if (!mountedSave.hasUUID("MountCarrier")) {
            helper.fail("a mount saved after its carrier unloaded lost its carrier");
            return;
        }
        Vec3 waiting = mounted.position();
        helper.runAfterDelay(20, () -> {
            if (mounted.position().distanceTo(waiting) > 1.0E-6) {
                helper.fail("a mount waiting for its carrier fell from where it was held: "
                        + waiting + " -> " + mounted.position());
                return;
            }
            Entity back = EntityType.loadEntityRecursive(carrierSave, helper.getLevel(), entity -> entity);
            if (!(back instanceof AbstractVehicle reloaded) || !helper.getLevel().addFreshEntity(reloaded)) {
                helper.fail("the carrier could not be read back");
                return;
            }
            reloaded.mountPoints = List.of(point);
            helper.runAfterDelay(30, () -> {
                boolean relinked = VehicleHarness.carrierOf(mounted) == reloaded;
                discard(reloaded, mounted);
                if (!relinked) {
                    helper.fail("the waiting mount never found its reloaded carrier");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** One point, one vehicle: a relink or a second mount onto an occupied point is refused. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aMountPointTakesOneVehicle(GameTestHelper helper) {
        AbstractVehicle carrier = spawn(helper, CARRIER, 2);
        AbstractVehicle first = spawn(helper, MOUNTED, 6);
        AbstractVehicle second = spawn(helper, MOUNTED, 9);
        if (carrier == null || first == null || second == null) {
            return;
        }
        carrier.updateOBBs();
        boolean took = VehicleHarness.mount(first, carrier, bed());
        boolean doubled = VehicleHarness.mount(second, carrier, bed());
        discard(carrier, first, second);
        if (!took) {
            helper.fail("the first mount was refused");
        } else if (doubled) {
            helper.fail("a second vehicle was mounted on an occupied point");
        } else {
            helper.succeed();
        }
    }

    /** A mounts B mounts C: moving A poses C off B's fresh pose in the same pass. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aNestedMountFollowsInTheSamePass(GameTestHelper helper) {
        AbstractVehicle bottom = spawn(helper, CARRIER, 2);
        AbstractVehicle middle = spawn(helper, CARRIER, 6);
        AbstractVehicle top = spawn(helper, MOUNTED, 10);
        if (bottom == null || middle == null || top == null) {
            return;
        }
        MountPointInfo point = bed();
        bottom.updateOBBs();
        if (!VehicleHarness.mount(middle, bottom, point) || !VehicleHarness.mount(top, middle, point)) {
            discard(bottom, middle, top);
            helper.fail("nesting was refused");
            return;
        }
        bottom.setPos(bottom.getX() + 7, bottom.getY(), bottom.getZ() + 3);
        bottom.setYRot(bottom.getYRot() + 25);
        bottom.updateOBBs();
        VehicleHarness.applyChildren(bottom);
        Vec3 expected = VehicleMounts.worldPositionOf(middle, point);
        double error = top.position().distanceTo(expected);
        discard(bottom, middle, top);
        if (error > 1.0E-3) {
            helper.fail(String.format(Locale.ROOT, "the top of the stack is %.3f off its mount after one pass", error));
            return;
        }
        helper.succeed();
    }

    /** Angles from actual eye position; an assumed angle could silently un-aim and masquerade as a feature failure. */
    private static FakePlayer aiming(GameTestHelper helper, AbstractVehicle carrier, MountPointInfo point) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "mount_tester"));
        Vec3 target = VehicleMounts.worldPositionOf(carrier, point);
        Vec3 stand = target.add(4, 0, 0);
        player.moveTo(stand.x, stand.y, stand.z, 0f, 0f);
        Vec3 toTarget = target.subtract(player.getEyePosition()).normalize();
        player.setYRot((float) -Math.toDegrees(Math.atan2(toTarget.x, toTarget.z)));
        player.setXRot((float) -Math.toDegrees(Math.asin(toTarget.y)));
        return player;
    }

    private static void discard(AbstractVehicle... vehicles) {
        for (AbstractVehicle vehicle : vehicles) {
            vehicle.discard();
        }
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id, int height) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, height, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle " + id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }

}
