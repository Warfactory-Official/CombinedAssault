package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.energy.FuelTransfer;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.VehicleSprayPod;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.mist.api.MistEntity;
import com.wf.wflib.mist.api.WFFluids;
import com.wf.wflib.mist.spray.SprayCurtainEntity;
import com.wf.wflib.warhead.api.AgentOrangeWarhead;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

/** Spray pod kit on the MH-60L door hardpoint: tank via the vehicle fluid handler, liquid curtain, gas puff. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SprayPodGameTest {

    private static final String TEMPLATE = "empty";
    private static final int RADIUS = 3;

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aPodTankFillsThroughTheVehicleHandlerAndFuelStaysFuel(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper);
        VehicleSprayPod pod = fit(heli);
        IFluidHandler handler = heli.fluids();
        int fuel = handler.fill(new FluidStack(heli.fuelSpec().defaultFuel(), 500), IFluidHandler.FluidAction.EXECUTE);
        int water = handler.fill(new FluidStack(Fluids.WATER, 700), IFluidHandler.FluidAction.EXECUTE);
        int lava = handler.fill(new FluidStack(Fluids.LAVA, 100), IFluidHandler.FluidAction.EXECUTE);
        int cap = pod.getMaxCapacity();
        FluidStack held = pod.getFluidInTank(0);
        FluidStack out = handler.drain(new FluidStack(Fluids.WATER, 200), IFluidHandler.FluidAction.EXECUTE);
        int left = pod.getRemainAmmo();
        heli.discard();
        helper.assertTrue(fuel == 500 && pod.getFluidInTank(0).getFluid() == Fluids.WATER, "fuel went to the pod: " + fuel);
        helper.assertTrue(water == 700 && lava == 100 && held.getAmount() == 700 && cap == 2000,
                "pods " + handler.getTanks() + " tank0 " + handler.getFluidInTank(0) + " water " + water + " lava " + lava + " held " + held.getAmount() + " cap " + cap);
        helper.assertTrue(out.getAmount() == 200 && left == 500, "drain " + out.getAmount() + " left " + left);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void defoliantSprayedFromAPodStripsTheTreeBelow(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper);
        VehicleSprayPod pod = fit(heli);
        pod.fill(new FluidStack(WFFluids.AGENT_ORANGE.get(), 400), IFluidHandler.FluidAction.EXECUTE);
        Vec3 muzzle = unit(heli).aimContext().from;
        BlockPos at = BlockPos.containing(muzzle);
        var level = helper.getLevel();
        BlockPos leaf = at.below(3);
        BlockPos outside = at.offset(RADIUS + 3, -3, 0);
        level.setBlockAndUpdate(leaf, Blocks.OAK_LEAVES.defaultBlockState());
        level.setBlockAndUpdate(leaf.below(), Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(leaf.below(2), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(outside, Blocks.OAK_LEAVES.defaultBlockState());
        helper.assertTrue(pod.getFluidInTank(0).getFluid() == WFFluids.AGENT_ORANGE.get(), "tank");
        pod.endCooldown();
        helper.assertTrue(pod.shoot(List.of(unit(heli).aimContext()), null), "pod refused to fire");
        helper.assertTrue(pod.getRemainAmmo() == 390, "mB left " + pod.getRemainAmmo());
        heli.discard();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getEntitiesOfClass(SprayCurtainEntity.class,
                        new AABB(at).inflate(40)).isEmpty(), "curtain still falling"))
                .thenExecute(() -> {
                    helper.assertTrue(!level.getBlockState(leaf).is(AgentOrangeWarhead.TARGETS), "leaf survived");
                    helper.assertTrue(!level.getBlockState(leaf.below()).is(AgentOrangeWarhead.TARGETS), "log survived");
                    helper.assertTrue(level.getBlockState(leaf.below(2)).is(Blocks.DIRT), "dirt turned");
                    helper.assertTrue(level.getBlockState(outside).is(Blocks.OAK_LEAVES), "leaf outside the disc turned");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aGasInThePodLeavesACloudAndEmptiesTheTank(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper);
        VehicleSprayPod pod = fit(heli);
        pod.fill(new FluidStack(WFFluids.TEAR_GAS.get(), 10), IFluidHandler.FluidAction.EXECUTE);
        Vec3 muzzle = unit(heli).aimContext().from;
        pod.endCooldown();
        boolean fired = pod.shoot(List.of(unit(heli).aimContext()), null);
        boolean emptied = pod.getRemainAmmo() == 0 && pod.getFluidInTank(0).isEmpty();
        boolean again = pod.shoot(List.of(unit(heli).aimContext()), null);
        heli.discard();
        helper.runAfterDelay(2, () -> {
            int cells = helper.getLevel().getEntitiesOfClass(MistEntity.class, new AABB(BlockPos.containing(muzzle)).inflate(12)).size();
            helper.assertTrue(fired && emptied && !again, "fired " + fired + " emptied " + emptied + " again " + again);
            helper.assertTrue(cells > 0, "no gas cells");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void onlyFluidsWithAPayloadFillAndFuelOverflowsToThePod(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper);
        VehicleSprayPod pod = fit(heli);
        int unregistered = pod.fill(new FluidStack(WFFluids.FLOWING_KEROSENE.get(), 100), IFluidHandler.FluidAction.EXECUTE);
        IFluidHandler handler = heli.fluids();
        int cap = heli.fuelTank().getTankCapacity(0);
        handler.fill(new FluidStack(heli.fuelSpec().defaultFuel(), cap), IFluidHandler.FluidAction.EXECUTE);
        int overflow = handler.fill(new FluidStack(heli.fuelSpec().defaultFuel(), 300), IFluidHandler.FluidAction.EXECUTE);
        FluidStack held = pod.getFluidInTank(0);
        heli.discard();
        helper.assertTrue(unregistered == 0, "fluid without payload filled: " + unregistered);
        helper.assertTrue(overflow == 300 && held.getAmount() == 300 && held.getFluid() == heli.fuelSpec().defaultFuel(),
                "overflow " + overflow + " held " + held + " default " + heli.fuelSpec().defaultFuel());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void refuellingFromACargoPodTakesTimeAndStopsOutOfRange(GameTestHelper helper) {
        AbstractVehicle source = spawn(helper, 1);
        AbstractVehicle receiver = spawn(helper, 6);
        VehicleSprayPod pod = fit(source);
        Fluid fuel = receiver.fuelSpec().defaultFuel();
        pod.fill(new FluidStack(fuel, 2000), IFluidHandler.FluidAction.EXECUTE);
        receiver.fuelTank().drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        helper.onEachTick(() -> {
            player.setPos(receiver.position().add(2, 0, 0));
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            FuelTransfer.tick(receiver);
        });
        helper.assertTrue(FuelTransfer.providers(receiver).contains(source), "source not offered");
        helper.assertTrue(FuelTransfer.start(receiver, player, source), "session refused");
        helper.runAfterDelay(FuelTransfer.CONNECT_TICKS / 4, () ->
                helper.assertTrue(receiver.fuelTank().storedMb() == 0, "flowed before the hose connected"));
        helper.runAfterDelay(FuelTransfer.CONNECT_TICKS + 20, () -> {
            int got = receiver.fuelTank().storedMb();
            helper.assertTrue(got > 0 && pod.getRemainAmmo() == 2000 - got, "got " + got + " pod " + pod.getRemainAmmo() + " session " + (receiver.refuel == null ? -1 : receiver.refuel.ticks()) + " tick " + receiver.tickCount + " dist " + player.distanceTo(receiver) + " lvl " + (player.level() == helper.getLevel()));
            source.setPos(source.position().add(60, 0, 0));
        });
        helper.runAfterDelay(FuelTransfer.CONNECT_TICKS + 30, () -> {
            helper.assertTrue(receiver.refuel == null, "session survived the source leaving");
            source.discard();
            receiver.discard();
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aWallBetweenTheVehiclesBlocksRefuelling(GameTestHelper helper) {
        AbstractVehicle source = spawn(helper, 1);
        AbstractVehicle receiver = spawn(helper, 8);
        VehicleSprayPod pod = fit(source);
        pod.fill(new FluidStack(receiver.fuelSpec().defaultFuel(), 500), IFluidHandler.FluidAction.EXECUTE);
        receiver.fuelTank().drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        boolean open = FuelTransfer.providers(receiver).contains(source);
        for (int y = 8; y <= 16; y++) {
            for (int z = -4; z <= 6; z++) {
                helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
            }
        }
        boolean walled = FuelTransfer.providers(receiver).contains(source);
        source.discard();
        receiver.discard();
        helper.assertTrue(open && !walled, "open " + open + " walled " + walled);
        helper.succeed();
    }

    private static WeaponUnit unit(AbstractVehicle heli) {
        return (WeaponUnit) heli.getPartUnit("door_gun_r").orElseThrow();
    }

    private static VehicleSprayPod fit(AbstractVehicle heli) {
        WeaponUnit unit = unit(heli);
        unit.fitKit(unit.hardpoint().indexOf(CombinedAssault.modLocation("spray_pod")));
        for (var weapon : unit.weapons) {
            if (weapon instanceof VehicleSprayPod pod) {
                return pod;
            }
        }
        throw new IllegalStateException("no spray pod after fitting: " + unit.weapons);
    }

    private static AbstractVehicle spawn(GameTestHelper helper) {
        return spawn(helper, 1);
    }

    private static AbstractVehicle spawn(GameTestHelper helper, int x) {
        ResourceLocation rl = CombinedAssault.modLocation("mh60l");
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl);
        BlockPos at = helper.absolutePos(new BlockPos(x, 10, 1));
        AbstractVehicle vehicle = data.orElseThrow().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        helper.getLevel().addFreshEntity(vehicle);
        return vehicle;
    }
}
