package com.wf.combinedassault.energy.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.energy.EnergyFluids;
import com.wf.combinedassault.energy.GregTechCompat;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.energy.VehicleFuelTank;
import com.wf.combinedassault.energy.VehicleResupply;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.energy.pojo.EnergyInfo;
import com.wf.combinedassault.energy.pojo.FuelEntry;

import java.util.Optional;

/**
 * Fuel as a real fluid, and charge as real Forge Energy.
 *
 * <p>The rules worth pinning down are the ones a player can feel: a tank takes one fluid and refuses the
 * rest until it runs dry, a worse fuel costs range rather than power, and an electric vehicle answers a
 * charger instead of a jerry can. The transfer helpers are exercised against a plain
 * {@link FluidTank} and {@link EnergyStorage} rather than a modded block, because what is being tested is
 * this side of the handshake.
 */
@PrefixGameTestTemplate(false)
public class VehicleEnergyGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRUCK = CombinedAssault.modLocation("humvee");
    private static final String FUEL_ID = "combinedassault:fuel";
    private static final String LAVA_ID = "minecraft:lava";
    /** Ticks each fuel is burnt for. Long enough to measure, short enough to fit the timeout twice. */
    private static final int WINDOW_TICKS = 40;

    /** One fluid at a time, and the tank only forgets when it is empty. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aTankHoldsOneFluidAtATime(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, fuelInfo(1000f, entry(FUEL_ID, 1f), entry(LAVA_ID, 0.5f)));
        if (vehicle == null) {
            return;
        }
        VehicleFuelTank tank = vehicle.fuelTank();
        int filled = tank.fill(fuel(400), IFluidHandler.FluidAction.EXECUTE);
        if (filled != 400 || tank.storedMb() != 400) {
            helper.fail("filling 400mB of fuel took " + filled + " and left " + tank.storedMb() + "mB in the tank");
            return;
        }
        if (Math.abs(vehicle.getEnergy() - 400f) > 1e-3) {
            helper.fail("400mB should read as 400 energy, reads " + vehicle.getEnergy());
            return;
        }
        int mixed = tank.fill(lava(400), IFluidHandler.FluidAction.EXECUTE);
        if (mixed != 0 || !tank.wouldMix(Fluids.LAVA)) {
            helper.fail("lava went into a tank of fuel: took " + mixed + "mB");
            return;
        }
        tank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        if (tank.storedMb() != 0 || !vehicle.fuelId().isEmpty()) {
            helper.fail("a drained tank still holds " + tank.storedMb() + "mB of " + vehicle.fuelId());
            return;
        }
        int nowLava = tank.fill(lava(400), IFluidHandler.FluidAction.EXECUTE);
        if (nowLava != 400 || !vehicle.fuelId().equals(LAVA_ID)) {
            helper.fail("an empty tank refused a different fuel: took " + nowLava + "mB of " + vehicle.fuelId());
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /** A fluid the pack never listed is not fuel, however much of it you pour. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void anUnlistedFluidIsNotFuel(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, fuelInfo(1000f, entry(FUEL_ID, 1f)));
        if (vehicle == null) {
            return;
        }
        if (vehicle.fuelSpec().accepts(Fluids.WATER)) {
            helper.fail("water counts as fuel");
            return;
        }
        int filled = vehicle.fuelTank().fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        if (filled != 0 || vehicle.getEnergy() != 0) {
            helper.fail("water filled the tank: took " + filled + "mB, energy " + vehicle.getEnergy());
            return;
        }
        // A fluid the pack did list, on the same vehicle, still goes in.
        if (vehicle.fuelTank().fill(fuel(100), IFluidHandler.FluidAction.EXECUTE) != 100) {
            helper.fail("the declared fuel was refused as well, so nothing was proven");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /** Efficiency is fuel economy: half as efficient burns twice as fast for the same power. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void efficiencyBuysRangeNotPower(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, fuelInfo(1000f, entry(FUEL_ID, 1f), entry(LAVA_ID, 0.5f)));
        if (vehicle == null) {
            return;
        }
        vehicle.fuelTank().fill(fuel(500), IFluidHandler.FluidAction.EXECUTE);
        float onFuel = vehicle.burnPerTick();
        vehicle.fuelTank().drain(1000, IFluidHandler.FluidAction.EXECUTE);
        vehicle.fuelTank().fill(lava(500), IFluidHandler.FluidAction.EXECUTE);
        float onLava = vehicle.burnPerTick();
        if (Math.abs(onLava - onFuel * 2f) > 1e-9) {
            helper.fail("lava at 0.5 efficiency should burn at " + (onFuel * 2f) + " per tick, burns at " + onLava);
            return;
        }
        if (Math.abs(onFuel - vehicle.energyInfo.energyConsumptionPerTick) > 1e-9) {
            helper.fail("a fuel at 1.0 efficiency should burn exactly what the pack declared");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /**
     * Running the engine on lava really does empty the tank twice as fast.
     *
     * <p>One vehicle, measured twice, rather than two measured once. Two vehicles cannot be made to run
     * the same amount: the arena is three blocks square, so the second one is parked outside it and
     * outside its chunk as often as not, and an entity in a chunk that is not entity-ticking does not
     * tick at all. Forcing the chunk narrowed the gap without closing it -- 60 power against 51 instead
     * of against 17 -- because the load still takes a few ticks. The same vehicle in the same block
     * cannot differ from itself.
     *
     * <p>Power is pinned at 100 rather than left to ramp, so the two windows are the same length in
     * engine-tick terms as well as in ticks: {@code tickPower} clamps, so writing 100 holds.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aWorseFuelRunsOutSooner(GameTestHelper helper) {
        EnergyInfo info = fuelInfo(1000f, entry(FUEL_ID, 1f), entry(LAVA_ID, 0.5f));
        info.energyConsumptionPerTick = 0.001f;
        AbstractVehicle vehicle = spawn(helper, 2, info);
        if (vehicle == null) {
            return;
        }
        vehicle.fuelTank().fill(fuel(500), IFluidHandler.FluidAction.EXECUTE);
        vehicle.toggleEngine(true);
        vehicle.setPower(100);
        float[] energy = new float[3];
        int[] ticks = new int[3];
        // Measured per tick the vehicle actually got, not per tick of the window. A gametest arena does
        // not promise an entity every tick -- a window that came out 24 ticks long against the 40 it
        // asked for is what this divides away -- and the ratio is what the test is about either way.
        helper.startSequence()
                .thenExecute(() -> {
                    energy[0] = vehicle.getEnergy();
                    ticks[0] = vehicle.tickCount;
                })
                .thenIdle(WINDOW_TICKS)
                .thenExecute(() -> {
                    energy[1] = vehicle.getEnergy();
                    ticks[1] = vehicle.tickCount;
                    requireRunning(helper, vehicle, ticks[1] - ticks[0], "the fuel window");
                    vehicle.fuelTank().drain(1000, IFluidHandler.FluidAction.EXECUTE);
                    vehicle.fuelTank().fill(lava(500), IFluidHandler.FluidAction.EXECUTE);
                    if (!vehicle.fuelId().equals(LAVA_ID)) {
                        helper.fail("the tank still holds " + vehicle.fuelId() + " after being refilled");
                    }
                    vehicle.setPower(100);
                    energy[2] = vehicle.getEnergy();
                    ticks[2] = vehicle.tickCount;
                })
                .thenIdle(WINDOW_TICKS)
                .thenExecute(() -> {
                    int lavaTicks = vehicle.tickCount - ticks[2];
                    requireRunning(helper, vehicle, lavaTicks, "the lava window");
                    float onFuel = (energy[0] - energy[1]) / (ticks[1] - ticks[0]);
                    float onLava = (energy[2] - vehicle.getEnergy()) / lavaTicks;
                    float ratio = onLava / onFuel;
                    if (Math.abs(ratio - 2f) > 0.05f) {
                        helper.fail("lava should burn twice as fast, burnt " + ratio + " times as much per "
                                + "tick (" + onLava + " against " + onFuel + ")");
                    }
                    vehicle.discard();
                })
                .thenSucceed();
    }

    /** Neither window means anything unless the engine was at full power for all of it. */
    private static void requireRunning(GameTestHelper helper, AbstractVehicle vehicle, int ticked, String window) {
        if (ticked <= 0) {
            helper.fail(window + " ran for " + ticked + " vehicle ticks, so nothing was measured");
        }
        if (vehicle.getPower() != 100f) {
            helper.fail("the engine was at " + vehicle.getPower() + " power at the end of " + window
                    + ", so this measures the engine rather than the fuel");
        }
    }

    /** An empty tank forgets its fuel, which is the only way to switch without a drain. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void runningDryClearsTheFuel(GameTestHelper helper) {
        EnergyInfo info = fuelInfo(1000f, entry(FUEL_ID, 1f), entry(LAVA_ID, 0.5f));
        info.energyConsumptionPerTick = 50f;
        AbstractVehicle vehicle = spawn(helper, 2, info);
        if (vehicle == null) {
            return;
        }
        vehicle.fuelTank().fill(fuel(60), IFluidHandler.FluidAction.EXECUTE);
        vehicle.toggleEngine(true);
        helper.runAfterDelay(80, () -> {
            if (vehicle.getEnergy() > 0) {
                helper.fail("the tank never ran dry, " + vehicle.getEnergy() + " left");
                return;
            }
            if (!vehicle.fuelId().isEmpty()) {
                helper.fail("a dry tank still claims to hold " + vehicle.fuelId());
                return;
            }
            if (vehicle.fuelTank().fill(lava(200), IFluidHandler.FluidAction.EXECUTE) != 200) {
                helper.fail("a dry tank would not take a different fuel");
                return;
            }
            vehicle.discard();
            helper.succeed();
        });
    }

    /** What is in the tank is part of the vehicle, not of the session. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aTankSurvivesSaveAndReload(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, fuelInfo(1000f, entry(FUEL_ID, 1f), entry(LAVA_ID, 0.5f)));
        if (vehicle == null) {
            return;
        }
        vehicle.fuelTank().fill(lava(750), IFluidHandler.FluidAction.EXECUTE);
        CompoundTag saved = new CompoundTag();
        vehicle.addAdditionalSaveData(saved);
        vehicle.discard();

        AbstractVehicle reloaded = spawn(helper, 2, 6, null);
        if (reloaded == null) {
            return;
        }
        reloaded.readAdditionalSaveData(saved);
        if (!reloaded.fuelId().equals(LAVA_ID)) {
            helper.fail("the reloaded tank holds " + reloaded.fuelId() + " instead of lava");
            return;
        }
        if (Math.abs(reloaded.getEnergy() - 750f) > 1e-3) {
            helper.fail("the reloaded tank holds " + reloaded.getEnergy() + " instead of 750");
            return;
        }
        reloaded.discard();
        helper.succeed();
    }

    /** A save from before tanks had a fluid still has fuel in it afterwards. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aLegacySaveKeepsItsFuel(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, null);
        if (vehicle == null) {
            return;
        }
        vehicle.setEnergy(500f);
        CompoundTag saved = new CompoundTag();
        vehicle.addAdditionalSaveData(saved);
        saved.remove("Fuel");
        vehicle.discard();

        AbstractVehicle reloaded = spawn(helper, 2, 6, null);
        if (reloaded == null) {
            return;
        }
        reloaded.readAdditionalSaveData(saved);
        if (Math.abs(reloaded.getEnergy() - 500f) > 1e-3) {
            helper.fail("a legacy save lost its fuel: " + reloaded.getEnergy());
            return;
        }
        if (!reloaded.fuelId().equals(FUEL_ID)) {
            helper.fail("a legacy tank should adopt the default fuel, adopted " + reloaded.fuelId());
            return;
        }
        reloaded.discard();
        helper.succeed();
    }

    /** Any tank size the pack asks for, in mB, and the gauge is that tank. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aTankCanBeAnySize(GameTestHelper helper) {
        for (int capacity : new int[]{250, 1000, 40000, 1_000_000}) {
            EnergyInfo info = fuelInfo(1000f, entry(FUEL_ID, 1f));
            info.tankCapacity = capacity;
            AbstractVehicle vehicle = spawn(helper, 2, info);
            if (vehicle == null) {
                return;
            }
            VehicleFuelTank tank = vehicle.fuelTank();
            if (tank.getTankCapacity(0) != capacity) {
                helper.fail("a " + capacity + "mB tank reports " + tank.getTankCapacity(0));
                return;
            }
            int filled = tank.fill(fuel(capacity * 2), IFluidHandler.FluidAction.EXECUTE);
            if (filled != capacity || tank.storedMb() != capacity) {
                helper.fail("a " + capacity + "mB tank took " + filled + "mB and holds " + tank.storedMb());
                return;
            }
            float expectedUnits = (float) capacity / EnergyInfo.MB_PER_UNIT;
            if (Math.abs(vehicle.getEnergy() - expectedUnits) > expectedUnits * 1e-4) {
                helper.fail("a full " + capacity + "mB tank reads " + vehicle.getEnergy()
                        + " energy, expected " + expectedUnits);
                return;
            }
            vehicle.discard();
        }
        helper.succeed();
    }

    /** Electric vehicles take a charge, at the rate they declared, and refuse a jerry can. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void anElectricVehicleTakesChargeNotFuel(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, electricInfo(1f, 20000, 1000));
        if (vehicle == null) {
            return;
        }
        if (vehicle.energyBuffer().getMaxEnergyStored() != 20000) {
            helper.fail("a one unit battery at 20000 FE/unit holds "
                    + vehicle.energyBuffer().getMaxEnergyStored() + " FE");
            return;
        }
        int taken = vehicle.energyBuffer().receiveEnergy(1_000_000, false);
        if (taken != 1000) {
            helper.fail("a 1000 FE/t charge rate accepted " + taken + " FE in one call");
            return;
        }
        if (Math.abs(vehicle.getEnergy() - 0.05f) > 1e-4) {
            helper.fail("1000 of 20000 FE should read as 0.05 energy, reads " + vehicle.getEnergy());
            return;
        }
        if (vehicle.fuelTank().fill(fuel(1000), IFluidHandler.FluidAction.EXECUTE) != 0) {
            helper.fail("an electric vehicle accepted fuel");
            return;
        }
        int overfilled = vehicle.addCharge(1_000_000);
        if (overfilled != 19000 || Math.abs(vehicle.getEnergy() - 1f) > 1e-4) {
            helper.fail("charging past full took " + overfilled + " FE and left " + vehicle.getEnergy());
            return;
        }
        if (vehicle.energyBuffer().extractEnergy(1000, false) != 0) {
            helper.fail("a cable drained a parked vehicle");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /** Parked over a tank, a fuel vehicle fills itself, but never with the wrong fluid. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void parkingOverATankRefuels(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, fuelInfo(4000f, entry(FUEL_ID, 1f), entry(LAVA_ID, 0.5f)));
        if (vehicle == null) {
            return;
        }
        FluidTank depot = new FluidTank(16000);
        depot.fill(fuel(16000), IFluidHandler.FluidAction.EXECUTE);
        int moved = VehicleResupply.pullFuel(vehicle, depot, 1000);
        if (moved != 1000 || vehicle.fuelTank().storedMb() != 1000) {
            helper.fail("pulling from a depot moved " + moved + "mB and left " + vehicle.fuelTank().storedMb());
            return;
        }
        FluidTank lavaDepot = new FluidTank(16000);
        lavaDepot.fill(lava(16000), IFluidHandler.FluidAction.EXECUTE);
        int mixed = VehicleResupply.pullFuel(vehicle, lavaDepot, 1000);
        if (mixed != 0 || lavaDepot.getFluidAmount() != 16000) {
            helper.fail("a pump mixed the tank: moved " + mixed + "mB of lava");
            return;
        }
        // On an empty tank, so what is being refused is the fluid rather than the mixing rule.
        AbstractVehicle thirsty = spawn(helper, 2, 6, fuelInfo(4000f, entry(FUEL_ID, 1f)));
        if (thirsty == null) {
            return;
        }
        FluidTank water = new FluidTank(16000);
        water.fill(new FluidStack(Fluids.WATER, 16000), IFluidHandler.FluidAction.EXECUTE);
        if (VehicleResupply.pullFuel(thirsty, water, 1000) != 0 || thirsty.getEnergy() != 0) {
            helper.fail("a water tank refuelled the vehicle");
            return;
        }
        vehicle.discard();
        thirsty.discard();
        helper.succeed();
    }

    /** Parked over a charger, an electric vehicle fills itself, and stops when it is full. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void parkingOverAChargerCharges(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, electricInfo(1f, 20000, 1000));
        if (vehicle == null) {
            return;
        }
        EnergyStorage supply = new EnergyStorage(100000, 100000, 100000, 100000);
        int moved = VehicleResupply.pullCharge(vehicle, supply, 5000);
        if (moved != 5000 || vehicle.energyBuffer().getEnergyStored() != 5000) {
            helper.fail("pulling from a charger moved " + moved
                    + " FE and left " + vehicle.energyBuffer().getEnergyStored());
            return;
        }
        int toFull = VehicleResupply.pullCharge(vehicle, supply, 100000);
        if (toFull != 15000) {
            helper.fail("topping up a 20000 FE battery from 5000 took " + toFull + " FE");
            return;
        }
        if (VehicleResupply.pullCharge(vehicle, supply, 100000) != 0) {
            helper.fail("a full battery kept drawing from the charger");
            return;
        }
        if (supply.getEnergyStored() != 80000) {
            helper.fail("the charger lost " + (100000 - supply.getEnergyStored())
                    + " FE while the vehicle gained 20000");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /** A fuel vehicle is not a battery, and an electric one is not a fuel tank. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void eachTypeOffersOnlyItsOwnHandler(GameTestHelper helper) {
        AbstractVehicle burner = spawn(helper, 2, fuelInfo(1000f, entry(FUEL_ID, 1f)));
        AbstractVehicle battery = spawn(helper, 2, 6, electricInfo(1f, 20000, 1000));
        if (burner == null || battery == null) {
            return;
        }
        if (burner.energyBuffer().receiveEnergy(1000, false) != 0 || burner.getEnergy() != 0) {
            helper.fail("a diesel vehicle took a charge");
            return;
        }
        FluidTank depot = new FluidTank(16000);
        depot.fill(fuel(16000), IFluidHandler.FluidAction.EXECUTE);
        if (VehicleResupply.pullFuel(battery, depot, 1000) != 0) {
            helper.fail("an electric vehicle drew fuel from a depot");
            return;
        }
        burner.discard();
        battery.discard();
        helper.succeed();
    }

    /** Without GregTech the EU bridge answers nothing rather than exploding. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void gregTechAbsentIsSilent(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, electricInfo(1f, 20000, 1000));
        if (vehicle == null) {
            return;
        }
        if (GregTechCompat.isAvailable()) {
            helper.fail("GregTech is loaded in this run, so this test proves nothing");
            return;
        }
        ItemStack battery = new ItemStack(Items.REDSTONE_BLOCK);
        if (GregTechCompat.dischargeHeldItem(vehicle, battery, 10000) != 0) {
            helper.fail("the EU bridge moved energy without GregTech");
            return;
        }
        if (GregTechCompat.pullFromBlock(vehicle, helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 1, 1)), net.minecraft.core.Direction.UP, 10000) != 0) {
            helper.fail("the EU bridge pulled from a block without GregTech");
            return;
        }
        if (GregTechCompat.euToFeRatio() != 4) {
            helper.fail("the fallback EU:FE ratio is " + GregTechCompat.euToFeRatio() + ", expected 4");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /** Fuel is weight the vehicle burns off; charge is weight it carries the whole way. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void chargeWeighsTheSameFullOrEmpty(GameTestHelper helper) {
        AbstractVehicle burner = spawn(helper, 2, fuelInfo(4000f, entry(FUEL_ID, 1f)));
        AbstractVehicle battery = spawn(helper, 2, 6, electricInfo(4f, 20000, 100000));
        if (burner == null || battery == null) {
            return;
        }
        burner.refreshMass();
        battery.refreshMass();
        float dryBurner = burner.physicsEngine.mass;
        float emptyBattery = battery.physicsEngine.mass;
        burner.fuelTank().fill(fuel(4000), IFluidHandler.FluidAction.EXECUTE);
        battery.addCharge(80000);
        if (Math.abs(burner.physicsEngine.mass - (dryBurner + 4000f)) > 1e-1) {
            helper.fail("four buckets of fuel changed mass from " + dryBurner + " to " + burner.physicsEngine.mass);
            return;
        }
        if (Math.abs(battery.physicsEngine.mass - emptyBattery) > 1e-6) {
            helper.fail("charging changed mass from " + emptyBattery + " to " + battery.physicsEngine.mass);
            return;
        }
        burner.discard();
        battery.discard();
        helper.succeed();
    }

    /** The tank and the battery are capabilities on the entity, not something only this mod can see. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void theTankIsVisibleAsACapability(GameTestHelper helper) {
        AbstractVehicle burner = spawn(helper, 2, fuelInfo(1000f, entry(FUEL_ID, 1f)));
        AbstractVehicle battery = spawn(helper, 2, 6, electricInfo(1f, 20000, 1000));
        if (burner == null || battery == null) {
            return;
        }
        IFluidHandler tank = burner.getCapability(Capabilities.FluidHandler.ENTITY, null);
        if (tank == null) {
            helper.fail("a fuel vehicle offers no fluid handler, so no pump will ever fill one");
            return;
        }
        if (tank.fill(fuel(400), IFluidHandler.FluidAction.EXECUTE) != 400 || burner.getEnergy() <= 0) {
            helper.fail("filling through the capability did not reach the vehicle");
            return;
        }
        if (burner.getCapability(Capabilities.EnergyStorage.ENTITY, null) != null) {
            helper.fail("a fuel vehicle offers a battery");
            return;
        }
        IEnergyStorage buffer = battery.getCapability(Capabilities.EnergyStorage.ENTITY, null);
        if (buffer == null) {
            helper.fail("an electric vehicle offers no energy storage, so no charger will ever fill one");
            return;
        }
        if (buffer.receiveEnergy(1000, false) != 1000 || battery.getEnergy() <= 0) {
            helper.fail("charging through the capability did not reach the vehicle");
            return;
        }
        if (battery.getCapability(Capabilities.FluidHandler.ENTITY, null) != null) {
            helper.fail("an electric vehicle offers a fuel tank");
            return;
        }
        burner.discard();
        battery.discard();
        helper.succeed();
    }

    /** Capabilities register per type => vehicle type missing from {@code AllEntities.vehicleTypes} answers none. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 20)
    public static void everyVehicleTypeOffersATank(GameTestHelper helper) {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals(CombinedAssault.MOD_ID)
                    || !(type.create(helper.getLevel()) instanceof AbstractVehicle vehicle) || vehicle instanceof VehiclePart) {
                continue;
            }
            if (vehicle.getCapability(Capabilities.FluidHandler.ENTITY, null) == null) {
                helper.fail(EntityType.getKey(type) + " offers no fuel tank");
                return;
            }
        }
        helper.succeed();
    }

    private static FuelEntry entry(String fluid, float efficiency) {
        return new FuelEntry(fluid, efficiency);
    }

    private static EnergyInfo fuelInfo(float capacityUnits, FuelEntry... fuels) {
        EnergyInfo info = new EnergyInfo();
        info.energyType = "fuel";
        info.energyCapacity = capacityUnits;
        for (FuelEntry fuel : fuels) {
            info.fuels.add(fuel);
        }
        return info;
    }

    private static EnergyInfo electricInfo(float capacityUnits, int fePerUnit, int chargeRate) {
        EnergyInfo info = new EnergyInfo();
        info.energyType = "electric";
        info.energyCapacity = capacityUnits;
        info.fePerUnit = fePerUnit;
        info.chargeRate = chargeRate;
        return info;
    }

    private static FluidStack fuel(int amount) {
        return new FluidStack(EnergyFluids.FUEL_SOURCE.get(), amount);
    }

    private static FluidStack lava(int amount) {
        return new FluidStack(Fluids.LAVA, amount);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, int height, @Nullable EnergyInfo info) {
        return spawn(helper, height, 1, info);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, int height, int x, @Nullable EnergyInfo info) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(TRUCK);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + TRUCK + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(x, height, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(TRUCK);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        if (info != null) {
            vehicle.setEnergyInfo(info);
        }
        return vehicle;
    }

}
