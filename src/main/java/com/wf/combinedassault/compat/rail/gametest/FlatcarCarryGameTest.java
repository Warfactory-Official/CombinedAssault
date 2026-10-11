package com.wf.combinedassault.compat.rail.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.compat.rail.RailCompat;
import com.wf.combinedassault.compat.rail.RailStock;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;

import java.util.Optional;

/** Vehicle strapped to IR stock. Needs {@code -PwithRail}; without IR, passes vacuously (compat inert). */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class FlatcarCarryGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation CARRIED = CombinedAssault.modLocation("humvee");

    private static MountPointInfo deck() {
        MountPointInfo point = new MountPointInfo();
        point.id = "deck";
        point.offset = new Vec3(0, 1, 0);
        point.yaw = 0;
        return point;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aFlatcarCarriesAVehicle(GameTestHelper helper) {
        if (!RailCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Entity stock = spawnFlatcar(helper);
        AbstractVehicle carried = spawnVehicle(helper);
        if (stock == null || carried == null) {
            return;
        }
        MountPointInfo point = deck();
        if (!RailCompat.mount(carried, stock, point)) {
            helper.fail("the flatcar refused the mount");
            return;
        }
        Vec3 at = RailStock.positionOf(stock);
        Vec3 expected = at.add(point.offset);
        if (carried.position().distanceTo(expected) > 1.0E-3) {
            helper.fail("mounting should have put the load at " + expected
                    + ", it is at " + carried.position());
            return;
        }

        // Pose by hand after moving the car: isolates carriage from tick order.
        Vec3 moved = at.add(7, 0, -3);
        RailStock.placeAt(stock, moved, 0);
        VehicleHarness.applyChildren(RailCompat.carrierFor(stock));
        Vec3 shunted = moved.add(point.offset);
        if (carried.position().distanceTo(shunted) > 1.0E-3) {
            helper.fail("after the car moved the load should be at " + shunted
                    + ", it is at " + carried.position());
            return;
        }

        RailStock.placeAt(stock, moved, 55);
        VehicleHarness.applyChildren(RailCompat.carrierFor(stock));
        if (Math.abs(Mth.degreesDifference(carried.getYRot(), 55 + point.yaw)) > 0.1f) {
            helper.fail("the load should face " + (55 + point.yaw) + ", faces " + carried.getYRot());
            return;
        }
        cleanUp(stock, carried);
        helper.succeed();
    }

    /** Identity invariant: same carrier object every call, else harness detaches next tick. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void theCarrierAdapterIsStable(GameTestHelper helper) {
        if (!RailCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Entity stock = spawnFlatcar(helper);
        if (stock == null) {
            return;
        }
        if (RailCompat.carrierFor(stock) != RailCompat.carrierFor(stock)) {
            helper.fail("two lookups of the same car gave different carriers");
            return;
        }
        stock.discard();
        helper.succeed();
    }

    @Nullable
    private static Entity spawnFlatcar(GameTestHelper helper) {
        String id = RailStock.findDefinition("flatcar");
        if (id == null) {
            helper.fail("IR is loaded but ships no flatcar definition to test with");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(3, 3, 3));
        Entity stock = RailStock.spawn(helper.getLevel(), id,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0);
        if (stock == null) {
            helper.fail("could not spawn " + id);
        }
        return stock;
    }

    @Nullable
    private static AbstractVehicle spawnVehicle(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(CARRIED);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + CARRIED + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 6, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(CARRIED);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        return vehicle;
    }

    private static void cleanUp(Entity stock, AbstractVehicle carried) {
        VehicleHarness.detach(carried, false);
        carried.discard();
        stock.discard();
    }
}
