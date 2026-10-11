package com.wf.combinedassault.vehicle.parenting.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.parenting.DeckCarrier;
import com.wf.combinedassault.vehicle.parenting.DeckFrame;
import com.wf.combinedassault.vehicle.parenting.DeckSnapshot;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link DeckCarrier} must not assume an {@link AbstractVehicle} or an entity (e.g. an IR flatcar). */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class NonVehicleCarrierGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation CARRIED = CombinedAssault.modLocation("humvee");

    private static final class Flatbed implements DeckCarrier {

        private final Level level;
        private final DeckSnapshot snapshot = new DeckSnapshot();
        private final List<AbstractVehicle> carried = new ArrayList<>();
        private final UUID uuid = UUID.randomUUID();
        private Vec3 pivot = Vec3.ZERO;
        private float yaw;

        Flatbed(Level level) {
            this.level = level;
        }

        void moveTo(Vec3 at, float newYaw) {
            this.pivot = at;
            this.yaw = newYaw;
            Quaternionf rotation = new Quaternionf();
            DeckFrame.fromEulerYXZ(newYaw, 0, 0, rotation);
            // 1 box: applyChildren skips a carrier that published none.
            snapshot.boxBuffer(1);
            snapshot.deckBoxBuffer(1);
            snapshot.set(rotation, newYaw, at.x, at.y, at.z, 1, 1);
        }

        @Override public DeckSnapshot deckSnapshot() { return snapshot; }
        @Override public List<AbstractVehicle> harnessedVehicles() { return carried; }
        @Override public Vec3 carrierCenterOffset() { return Vec3.ZERO; }
        @Override public Level level() { return level; }
        @Override public boolean isRemoved() { return false; }
        @Override public Entity.RemovalReason getRemovalReason() { return null; }
        @Override public Vec3 position() { return pivot; }
        @Override public int getId() { return 987654; }
        @Override public UUID getUUID() { return uuid; }
        @Override public double lerpTargetX() { return pivot.x; }
        @Override public double lerpTargetY() { return pivot.y; }
        @Override public double lerpTargetZ() { return pivot.z; }
        @Override public float lerpTargetXRot() { return 0; }
        @Override public float lerpTargetYRot() { return yaw; }
        @Override public float lerpTargetZRot() { return 0; }
    }

    private static MountPointInfo bed() {
        MountPointInfo point = new MountPointInfo();
        point.id = "bed";
        point.offset = new Vec3(0, 2, -1.5);
        point.yaw = 90;
        return point;
    }

    /** Unrotated: mount = pivot + offset. Pure translation: load moves by the same delta. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aFlatbedCarriesAVehicle(GameTestHelper helper) {
        AbstractVehicle carried = spawn(helper, CARRIED, 4);
        if (carried == null) {
            return;
        }
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        Flatbed flatbed = new Flatbed(helper.getLevel());
        flatbed.moveTo(origin, 0);

        MountPointInfo point = bed();
        if (!VehicleHarness.mount(carried, flatbed, point)) {
            helper.fail("a non-vehicle carrier was refused the mount");
            return;
        }
        if (VehicleHarness.carrierOf(carried) != flatbed) {
            helper.fail("the harness did not record the flatbed as the carrier");
            return;
        }
        Vec3 expected = origin.add(point.offset);
        if (carried.position().distanceTo(expected) > 1.0E-4) {
            helper.fail("mounting should have put it at " + expected + ", it is at " + carried.position());
            return;
        }

        Vec3 delta = new Vec3(9, 1, -4);
        flatbed.moveTo(origin.add(delta), 0);
        VehicleHarness.applyChildren(flatbed);
        if (carried.position().distanceTo(expected.add(delta)) > 1.0E-3) {
            helper.fail("after the flatbed moved the load should be at " + expected.add(delta)
                    + ", it is at " + carried.position());
            return;
        }

        flatbed.moveTo(origin.add(delta), 40);
        VehicleHarness.applyChildren(flatbed);
        if (Math.abs(Mth.degreesDifference(carried.getYRot(), 40 + point.yaw)) > 0.1f) {
            helper.fail("the load should face " + (40 + point.yaw) + ", faces " + carried.getYRot());
            return;
        }

        VehicleHarness.releaseAll(flatbed, false);
        if (VehicleHarness.isHarnessed(carried)) {
            helper.fail("releaseAll left the vehicle harnessed to the flatbed");
            return;
        }
        carried.discard();
        helper.succeed();
    }

    /** Mount != park: engine start and wake must not release it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aFlatbedMountIsNotAPark(GameTestHelper helper) {
        AbstractVehicle carried = spawn(helper, CARRIED, 4);
        if (carried == null) {
            return;
        }
        Flatbed flatbed = new Flatbed(helper.getLevel());
        flatbed.moveTo(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(3, 2, 3))), 0);
        if (!VehicleHarness.mount(carried, flatbed, bed())) {
            helper.fail("a non-vehicle carrier was refused the mount");
            return;
        }
        carried.toggleEngine(true);
        VehicleHarness.tick(carried);
        if (!VehicleHarness.isMounted(carried)) {
            helper.fail("starting the engine released a flatbed mount");
            return;
        }
        VehicleHarness.wake(carried);
        if (!VehicleHarness.isMounted(carried)) {
            helper.fail("wake released a flatbed mount");
            return;
        }
        VehicleHarness.releaseAll(flatbed, false);
        carried.discard();
        helper.succeed();
    }

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
