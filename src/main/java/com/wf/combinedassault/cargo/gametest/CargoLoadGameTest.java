package com.wf.combinedassault.cargo.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.vehicle.entity.TrackedVehicle;
import com.wf.combinedassault.cargo.ItemMass;
import com.wf.combinedassault.cargo.UltimateWeightCompat;
import com.wf.wflib.ammo.api.CartridgeItem;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;
import java.util.Optional;

/** Hold contents -> mass (built in, no Ultimate Weight here) -> load: drive, inertia, fuel, overload. */
@PrefixGameTestTemplate(false)
public class CargoLoadGameTest {

    private static final String TEMPLATE = "empty";
    /** > CargoMass recount throttle. */
    private static final int RECOUNT = 25;

    private static AbstractVehicle spawn(GameTestHelper helper, String id, int x) {
        ResourceLocation rl = CombinedAssault.modLocation(id);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl);
        if (data.isEmpty()) {
            throw new IllegalStateException(id + " not in the roster");
        }
        BlockPos at = helper.absolutePos(new BlockPos(x, 20, 1));
        // Off-arena chunk => entity not ticked => hold never recounted.
        ForcedChunks.set(helper.getLevel(), at.getX() >> 4, at.getZ() >> 4, true);
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        helper.getLevel().addFreshEntity(vehicle);
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        vehicle.toggleEngine(true);
        vehicle.setPower(100);
        return vehicle;
    }

    private static void release(AbstractVehicle... vehicles) {
        for (AbstractVehicle vehicle : vehicles) {
            ForcedChunks.set((ServerLevel) vehicle.level(), vehicle.getBlockX() >> 4, vehicle.getBlockZ() >> 4, false);
            vehicle.discard();
        }
    }

    private static void fill(AbstractVehicle vehicle, ItemStack stack, int slots) {
        for (int i = 0; i < slots; i++) {
            vehicle.setItem(i, stack.copy());
        }
    }

    private static void close(GameTestHelper helper, double actual, double expected, double tolerance, String what) {
        helper.assertTrue(Math.abs(actual - expected) <= tolerance * Math.abs(expected),
                String.format(Locale.ROOT, "%s: %.4f, expected %.4f", what, actual, expected));
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void massesWeighTheHold(GameTestHelper helper) {
        AbstractVehicle truck = spawn(helper, "humvee", 1);
        CartridgeItem mortar = (CartridgeItem) BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("wflib", "cartridge_81mm"));
        ItemStack[] stacks = {new ItemStack(Items.IRON_BLOCK, 64), new ItemStack(Items.STONE, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.IRON_INGOT, 9), new ItemStack(mortar, 4)};
        double[] builtIn = {64 * 7.87, 64 * 2.6, 64 * 0.6, 9 * 0.114 * 7.87, 4 * mortar.calibre().massKg()};
        double expected = 0;
        for (int i = 0; i < stacks.length; i++) {
            double uw = UltimateWeightCompat.kilograms(stacks[i]);
            expected += uw > 0 ? uw : builtIn[i];
            truck.setItem(i, stacks[i].copy());
        }
        double hold = expected;
        helper.runAfterDelay(RECOUNT, () -> {
            double cargo = truck.cargoKilograms();
            float synced = truck.getEntityData().get(AbstractVehicle.CARGO_MASS);
            float mass = truck.physicsEngine.mass;
            float unladen = truck.unladenMass();
            release(truck);
            close(helper, ItemMass.kilograms(new ItemStack(Items.IRON_INGOT)), 0.897, 0.01, "iron ingot");
            close(helper, cargo, hold, 1e-4, "hold kg");
            close(helper, synced, cargo * truck.cargoMassPerKg, 1e-4, "synced cargo mass");
            close(helper, mass, unladen + synced, 1e-4, "physics mass");
            helper.succeed();
        });
    }

    /** One tick from rest: tracked FORWARD_SPEED = forward_acceleration / load. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aLoadedTankAcceleratesSlower(GameTestHelper helper) {
        TrackedVehicle empty = (TrackedVehicle) spawn(helper, "m1a2", 1);
        TrackedVehicle loaded = (TrackedVehicle) spawn(helper, "m1a2", 14);
        fill(loaded, new ItemStack(Items.IRON_BLOCK, 64), 54);
        helper.runAfterDelay(RECOUNT, () -> {
            for (TrackedVehicle tank : new TrackedVehicle[]{empty, loaded}) {
                tank.autopilotControl = true;
                tank.controlUnit.forward = true;
                tank.setDeltaMovement(Vec3.ZERO);
                tank.getEntityData().set(TrackedVehicle.FORWARD_SPEED, 0f);
            }
            helper.runAfterDelay(1, () -> {
                double load = loaded.load();
                float fe = empty.getForwardSpeed();
                float fl = loaded.getForwardSpeed();
                release(empty, loaded);
                helper.assertTrue(load > 1.3, "54 stacks of iron blocks: load " + load);
                close(helper, fe / fl, load, 0.02, "empty / loaded acceleration");
                helper.succeed();
            });
        });
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void fuelBurnScalesWithLoad(GameTestHelper helper) {
        AbstractVehicle truck = spawn(helper, "humvee", 1);
        float before = truck.burnPerTick();
        fill(truck, new ItemStack(Items.STONE, 64), 5);
        helper.runAfterDelay(RECOUNT, () -> {
            float after = truck.burnPerTick();
            double load = truck.load();
            release(truck);
            helper.assertTrue(load > 1.3, "5 stacks of stone in a humvee: load " + load);
            close(helper, after / before, load, 1e-3, "burn ratio");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void pastMaxPayloadTheEngineStrains(GameTestHelper helper) {
        AbstractVehicle truck = spawn(helper, "humvee", 1);
        fill(truck, new ItemStack(Items.IRON_BLOCK, 64), 4);
        helper.runAfterDelay(RECOUNT, () -> {
            double over = truck.overload();
            double strain = truck.strain();
            release(truck);
            close(helper, over, truck.cargoMass() / truck.maxPayload, 1e-6, "overload");
            helper.assertTrue(over > 2, "4 stacks of iron blocks vs " + truck.maxPayload + " kg: " + over);
            close(helper, strain, 1 / (over * over), 1e-6, "strain");
            helper.succeed();
        });
    }

    /** One tick from rest: yaw rate = yaw acceleration x rotor scale / load - friction. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aLoadedHelicopterYawsSlower(GameTestHelper helper) {
        RotaryWingVehicle empty = (RotaryWingVehicle) spawn(helper, "ah64d", 1);
        RotaryWingVehicle loaded = (RotaryWingVehicle) spawn(helper, "ah64d", 10);
        fill(loaded, new ItemStack(Items.IRON_BLOCK, 64), 5);
        helper.runAfterDelay(RECOUNT, () -> {
            for (RotaryWingVehicle heli : new RotaryWingVehicle[]{empty, loaded}) {
                heli.autopilotControl = true;
                heli.controlUnit.rightYaw = true;
                heli.setCollectivePitch(50);
                heli.setDeltaMovement(Vec3.ZERO);
                heli.yRotSpeed = 0;
            }
            helper.runAfterDelay(1, () -> {
                double load = loaded.load();
                float ye = empty.yRotSpeed + RotaryWingVehicle.ROT_FRICTION;
                float yl = loaded.yRotSpeed + RotaryWingVehicle.ROT_FRICTION;
                release(empty, loaded);
                helper.assertTrue(load > 1.3 && ye > 0, "load " + load + ", empty yaw rate " + ye);
                close(helper, ye / yl, load, 0.02, "empty / loaded yaw acceleration");
                helper.succeed();
            });
        });
    }
}
