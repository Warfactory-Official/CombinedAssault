package com.wf.combinedassault.storage.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.storage.HoldGrid;
import com.wf.combinedassault.storage.VehicleStorageMenu;
import com.wf.combinedassault.vehicle.pojo.StorageInfo;

import java.util.Optional;
import java.util.UUID;

/** Arbitrary-size cargo holds: counts picked awkward (10 sub-window, 100 multi-window, 1000 off-screen) to stress layout/shift-click/resize/mass once hold != a chest. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class VehicleStorageGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRUCK = CombinedAssault.modLocation("humvee");
    /** No {@code storage} block. */
    private static final ResourceLocation UNDECLARED = CombinedAssault.modLocation("m1a2");

    /** Mirrors private {@link HoldGrid#FLOOR_WIDTH}/{@code FLOOR_HEIGHT}; keep in sync. */
    private static final int FLOOR_WIDTH = 320;
    private static final int FLOOR_HEIGHT = 240;

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void anySlotCountIsAccepted(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2);
        if (vehicle == null) {
            return;
        }
        for (int slots : new int[]{0, 1, 10, 54, 100, 999, 1000}) {
            vehicle.setContainerSize(slots);
            if (vehicle.getContainerSize() != slots) {
                helper.fail("asked for " + slots + " slots, got " + vehicle.getContainerSize());
                return;
            }
            VehicleStorageMenu menu = menu(helper, vehicle);
            if (menu.holdSlots() != slots) {
                helper.fail("the menu built " + menu.holdSlots() + " hold slots for a " + slots + "-slot hold");
                return;
            }
            if (menu.slots.size() != slots + 36) {
                helper.fail("a " + slots + "-slot hold should give " + (slots + 36)
                        + " menu slots with the player inventory, gave " + menu.slots.size());
                return;
            }
        }
        vehicle.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void theGridIsBuiltForTheHoldAndNotForAChest(GameTestHelper helper) {
        HoldGrid small = HoldGrid.fit(5, FLOOR_WIDTH, FLOOR_HEIGHT);
        if (small.columns() != 5 || small.rows() != 1 || small.scrollable()) {
            helper.fail("a five slot hold should be one row of five, got " + small.columns() + " by "
                    + small.rows() + (small.scrollable() ? " and scrolling" : ""));
            return;
        }
        HoldGrid ten = HoldGrid.fit(10, FLOOR_WIDTH, FLOOR_HEIGHT);
        if (ten.columns() != 9 || ten.rows() != 2 || ten.windowRows() != 2 || ten.scrollable()) {
            helper.fail("a ten slot hold should be two rows on view, got " + ten.rows() + " rows with "
                    + ten.windowRows() + " on view");
            return;
        }
        HoldGrid chest = HoldGrid.fit(StorageInfo.DEFAULT_SLOTS, FLOOR_WIDTH, FLOOR_HEIGHT);
        if (chest.columns() != 9 || chest.windowRows() != 6 || chest.scrollable()) {
            helper.fail("fifty four slots should still be six rows with nothing to scroll, got "
                    + chest.windowRows() + " rows on view");
            return;
        }
        HoldGrid deep = HoldGrid.fit(1000, FLOOR_WIDTH, FLOOR_HEIGHT);
        if (!deep.scrollable() || deep.rows() != 112 || deep.maxScroll() != deep.rows() - deep.windowRows()) {
            helper.fail("a thousand slots should scroll 112 rows through a " + deep.windowRows()
                    + " row window, maxScroll is " + deep.maxScroll());
            return;
        }
        HoldGrid roomy = HoldGrid.fit(1000, 640, 360);
        if (roomy.columns() <= deep.columns() || roomy.columns() % 9 != 0
                || roomy.rows() >= deep.rows() || roomy.width() > 640 - 16) {
            helper.fail("a bigger window should buy columns in nines, got " + roomy.columns()
                    + " columns in a panel " + roomy.width() + " wide");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void theWindowShowsOnlyTheRowsScrolledTo(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2);
        if (vehicle == null) {
            return;
        }
        vehicle.setContainerSize(1000);
        VehicleStorageMenu menu = menu(helper, vehicle);
        menu.layout(HoldGrid.fit(1000, FLOOR_WIDTH, FLOOR_HEIGHT));
        HoldGrid grid = menu.grid();
        int stride = grid.columns() * grid.windowRows();

        menu.scrollTo(0);
        int topY = menu.slots.get(0).y;
        if (!menu.slots.get(0).isActive() || menu.slots.get(stride).isActive()) {
            helper.fail("at the top of the hold, only the first " + grid.windowRows() + " rows may be real");
            return;
        }
        menu.scrollTo(grid.windowRows());
        if (menu.slots.get(0).isActive() || !menu.slots.get(stride).isActive()) {
            helper.fail("scrolled one window down, the first window's slots must have gone");
            return;
        }
        if (menu.slots.get(stride).y != topY) {
            helper.fail("the row scrolled to should sit where the top row was, at " + topY
                    + ", it is at " + menu.slots.get(stride).y);
            return;
        }
        menu.scrollTo(99999);
        if (menu.scrollRow() != grid.maxScroll()) {
            helper.fail("scrolling past the end should clamp to " + grid.maxScroll()
                    + ", landed on " + menu.scrollRow());
            return;
        }
        if (!menu.slots.get(menu.holdSlots() - 1).isActive()) {
            helper.fail("the last slot in the hold is off screen at the bottom of the scroll");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void shiftClickFillsPastTheVisibleWindow(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2);
        if (vehicle == null) {
            return;
        }
        vehicle.setContainerSize(200);
        // Stone fill (won't merge w/ diamond) forces landing at slot 54, below the window.
        for (int slot = 0; slot < StorageInfo.DEFAULT_SLOTS; slot++) {
            vehicle.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        FakePlayer player = player(helper);
        VehicleStorageMenu menu = new VehicleStorageMenu(1, player.getInventory(), vehicle);
        menu.scrollTo(0);
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 3));

        menu.quickMoveStack(player, 200);

        ItemStack landed = vehicle.getItem(StorageInfo.DEFAULT_SLOTS);
        if (!landed.is(Items.DIAMOND) || landed.getCount() != 3) {
            helper.fail("shift-click should have filled slot 54 below the window, found " + landed
                    + " (player still holds " + player.getInventory().getItem(9) + ")");
            return;
        }
        vehicle.discard();
        player.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void resizingKeepsWhatStillFits(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2);
        if (vehicle == null) {
            return;
        }
        vehicle.setContainerSize(100);
        vehicle.setItem(10, new ItemStack(Items.IRON_INGOT, 5));
        vehicle.setItem(80, new ItemStack(Items.GOLD_INGOT, 7));

        vehicle.setContainerSize(200);
        if (!vehicle.getItem(80).is(Items.GOLD_INGOT)) {
            helper.fail("growing a hold must not disturb what is in it; slot 80 held " + vehicle.getItem(80));
            return;
        }
        vehicle.setContainerSize(50);
        if (!vehicle.getItem(10).is(Items.IRON_INGOT)) {
            helper.fail("shrinking a hold must keep what still fits; slot 10 held " + vehicle.getItem(10));
            return;
        }
        if (vehicle.getContainerSize() != 50) {
            helper.fail("the hold should have shrunk to 50, is " + vehicle.getContainerSize());
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void undeclaredHoldsKeepTheirOldSize(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, 2, UNDECLARED);
        if (vehicle == null) {
            return;
        }
        if (vehicle.getContainerSize() != StorageInfo.DEFAULT_SLOTS) {
            helper.fail("a vehicle that declares no storage should still have "
                    + StorageInfo.DEFAULT_SLOTS + " slots, has " + vehicle.getContainerSize());
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void refuellingKeepsTheDeclaredMass(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(TRUCK);
        AbstractVehicle vehicle = spawn(helper, 2);
        if (vehicle == null || data.isEmpty()) {
            return;
        }
        float declared = data.get().getPhysicsInfo().mass;
        if (vehicle.curbWeight != declared) {
            helper.fail("curb weight should be the declared mass " + declared + ", is " + vehicle.curbWeight);
            return;
        }
        vehicle.curbWeight = 5000f;
        vehicle.setEnergy(0.5f);
        if (Math.abs(vehicle.physicsEngine.mass - 5000.5f) > 1.0E-4) {
            helper.fail("refuelling should add to the curb weight, not replace it; mass is "
                    + vehicle.physicsEngine.mass);
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    private static VehicleStorageMenu menu(GameTestHelper helper, AbstractVehicle vehicle) {
        return new VehicleStorageMenu(1, player(helper).getInventory(), vehicle);
    }

    private static FakePlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "storage_tester"));
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, int height) {
        return spawn(helper, height, TRUCK);
    }

    private static AbstractVehicle spawn(GameTestHelper helper, int height, ResourceLocation id) {
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
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        return vehicle;
    }

}
