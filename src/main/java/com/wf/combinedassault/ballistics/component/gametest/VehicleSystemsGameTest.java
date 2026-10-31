package com.wf.combinedassault.ballistics.component.gametest;

import com.wf.wflib.probe.api.ProbeActions;
import com.wf.wflib.probe.api.ProbeJobs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.SystemRepair;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.List;
import java.util.Optional;
import com.wf.combinedassault.ballistics.BallisticsContent;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleSystemsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation TRUCK = CombinedAssault.modLocation("m983");

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void tankCellsMatchTheHud(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        List<String> labels = VehicleSystems.cells(vehicle).stream().map(VehicleSystems.Cell::label).toList();
        if (!labels.equals(List.of("HULL", "ENG", "GUN", "L-TR", "R-TR", "TRRT"))) {
            helper.fail("m1a2 cells " + labels);
            return;
        }
        AbstractVehicle truck = spawn(helper, TRUCK);
        if (truck == null) {
            return;
        }
        List<String> truckLabels = VehicleSystems.cells(truck).stream().map(VehicleSystems.Cell::label).toList();
        if (!truckLabels.equals(List.of("HULL", "ENG", "L-WHL", "R-WHL"))) {
            helper.fail("m983 cells " + truckLabels + ", hit nodes " + (truck.hits() == null ? "none"
                    : truck.hits().model().nodes().stream().filter(n -> n.kind() != null)
                    .map(n -> n.mesh().name() + "=" + n.systemId() + (n.part() == null ? "" : "@" + n.part())).toList()));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aDeadHullIsNotADeadVehicle(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        vehicle.setHealth(0);
        vehicle.hurt(helper.getLevel().damageSources().generic(), 1);
        if (vehicle.isDestroyed()) {
            helper.fail("destroyed with only the hull gone");
            return;
        }
        VehicleSystems.overflow(vehicle, 1.0e6f);
        if (!VehicleSystems.allFailed(vehicle)) {
            helper.fail("overflow left a system standing: " + VehicleSystems.cells(vehicle).stream()
                    .filter(c -> !c.failed()).map(VehicleSystems.Cell::label).toList());
            return;
        }
        vehicle.hurt(helper.getLevel().damageSources().generic(), 1);
        if (!vehicle.isDestroyed()) {
            helper.fail("every system failed and the vehicle lives");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aDeadEngineWillNotStart(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TRUCK);
        if (vehicle == null) {
            return;
        }
        HullComponents pools = vehicle.hullComponents();
        pools.setHealth(pools.indexOf("engine"), 0);
        vehicle.toggleEngine(true);
        if (vehicle.isEngineOn()) {
            helper.fail("a broken engine started");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aRepairStepsOneBand(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        VehicleSystems.Cell track = leftTrack(helper, vehicle);
        if (track == null) {
            return;
        }
        for (VehicleSystems.Member member : track.members()) {
            member.setHealth(member.maxHealth() * 0.05f);
        }
        ComponentState[] expect = {ComponentState.DAMAGED, ComponentState.WORN, ComponentState.PRISTINE};
        for (ComponentState state : expect) {
            track.step();
            if (track.state() != state) {
                helper.fail("step reached " + track.state() + " (" + track.ratio() + ") not " + state);
                return;
            }
        }
        if (track.ratio() < 1 || track.step()) {
            helper.fail("the last step did not reach full, or a full system stepped");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aTimedRepairNeedsACarriedToolAndStopsWhenYouWalkOffNotWhenCarried(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        VehicleSystems.Cell track = leftTrack(helper, vehicle);
        if (track == null) {
            return;
        }
        for (VehicleSystems.Member member : track.members()) {
            member.setHealth(member.maxHealth() * 0.3f); // DAMAGED
        }
        ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        Vec3 beside = vehicle.position().add(3, 0, 0);
        player.setPos(beside.x, beside.y, beside.z);
        // Tank wider than the arena: its barrier wall stands between => no probe sight.
        BlockPos.betweenClosed(BlockPos.containing(vehicle.position().add(-6, -1, -6)),
                BlockPos.containing(vehicle.position().add(8, 4, 6))).forEach(pos -> {
            if (helper.getLevel().getBlockState(pos).is(Blocks.BARRIER)) {
                helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        });

        ProbeActions.performEntity(player, vehicle, SystemRepair.ACTION, track.key());
        if (ProbeJobs.busy(player)) {
            helper.fail("started with no tool");
            return;
        }
        ItemStack tool = new ItemStack(BallisticsContent.REPAIR_TOOL.get());
        player.getInventory().setItem(20, tool); // backpack, not hand
        ProbeActions.performEntity(player, vehicle, SystemRepair.ACTION, track.key());
        if (!ProbeJobs.busy(player)) {
            helper.fail("did not start with the tool in the backpack");
            return;
        }
        player.setPos(beside.x + 2, beside.y, beside.z);
        ProbeJobs.tickAll();
        if (ProbeJobs.busy(player) || track.state() != ComponentState.DAMAGED) {
            helper.fail("walking off did not stop the repair");
            return;
        }
        player.setPos(beside.x, beside.y, beside.z);
        ProbeActions.performEntity(player, vehicle, SystemRepair.ACTION, track.key());
        // Both carried by one deck: offset kept => the repair goes on.
        vehicle.setPos(vehicle.getX(), vehicle.getY(), vehicle.getZ() + 3);
        player.setPos(beside.x, beside.y, beside.z + 3);
        ProbeJobs.tickAll();
        if (!ProbeJobs.busy(player)) {
            helper.fail("player and vehicle carried together, and the repair stopped");
            return;
        }
        for (int i = 0; i < track.stepTicks() && ProbeJobs.busy(player); i++) {
            ProbeJobs.tickAll();
        }
        if (ProbeJobs.busy(player) || track.state() != ComponentState.WORN) {
            helper.fail("a finished repair left the track " + track.state());
            return;
        }
        if (tool.getDamageValue() != ComponentKind.TRACK.durability) {
            helper.fail("tool wore " + tool.getDamageValue() + " not " + ComponentKind.TRACK.durability);
            return;
        }
        helper.succeed();
    }

    @Nullable
    private static VehicleSystems.Cell leftTrack(GameTestHelper helper, AbstractVehicle vehicle) {
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            if (cell.kind() == ComponentKind.TRACK && cell.side() == VehicleSystems.Side.LEFT) {
                return cell;
            }
        }
        helper.fail("no left track cell");
        return null;
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        vehicle.updateOBBs();
        return vehicle;
    }
}
