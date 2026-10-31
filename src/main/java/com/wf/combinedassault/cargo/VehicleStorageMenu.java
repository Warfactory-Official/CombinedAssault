package com.wf.combinedassault.cargo;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * All hold slots exist in the menu always; scroll changes only draw position + {@link WindowSlot#isActive()}, never
 * slot count/order/indices. {@link Slot#x}/{@link Slot#y} are vanilla-final, AT'd writable here so scroll = move, not rebuild.
 * Scroll is client-only state; server never reads it (a click already names an index).
 */
public class VehicleStorageMenu extends AbstractContainerMenu {

    private final Container hold;
    private final int holdSlots;

    private HoldGrid grid;

    /** Client-only; see class javadoc. */
    private int scrollRow;

    /** Server: hold = vehicle's live container. */
    public VehicleStorageMenu(int containerId, Inventory playerInventory, Container hold) {
        super(CargoContent.VEHICLE_STORAGE.get(), containerId);
        this.hold = hold;
        this.holdSlots = hold.getContainerSize();
        this.grid = HoldGrid.unmeasured(this.holdSlots);
        hold.startOpen(playerInventory.player);
        addHoldSlots();
        addPlayerSlots(playerInventory);
        place();
    }

    /** Client: scratch container sized from the wire; filled by content sync, never points at the real (server-authoritative) container. */
    public VehicleStorageMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory, new SimpleContainer(Math.max(0, data.readVarInt())));
    }

    private void addHoldSlots() {
        for (int index = 0; index < holdSlots; index++) {
            addSlot(new WindowSlot(hold, index));
        }
    }

    private void addPlayerSlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < HoldGrid.STRIDE; column++) {
                addSlot(new Slot(playerInventory, column + row * HoldGrid.STRIDE + HoldGrid.STRIDE, 0, 0));
            }
        }
        for (int column = 0; column < HoldGrid.STRIDE; column++) {
            addSlot(new Slot(playerInventory, column, 0, 0));
        }
    }

    public int holdSlots() {
        return holdSlots;
    }

    public HoldGrid grid() {
        return grid;
    }

    public int scrollRow() {
        return scrollRow;
    }

    /** Re-clamps scroll into the new grid's range. */
    public void layout(HoldGrid measured) {
        this.grid = measured;
        this.scrollRow = Mth.clamp(scrollRow, 0, measured.maxScroll());
        place();
    }

    public void scrollTo(int row) {
        int target = Mth.clamp(row, 0, grid.maxScroll());
        if (target != scrollRow) {
            scrollRow = target;
            place();
        }
    }

    public int filledSlots() {
        int filled = 0;
        for (int index = 0; index < holdSlots; index++) {
            if (!hold.getItem(index).isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** Off-window rows get moved too, not frozen; {@link WindowSlot#isActive()} is what actually keeps them unreachable. */
    private void place() {
        int columns = grid.columns();
        for (int index = 0; index < holdSlots; index++) {
            Slot slot = slots.get(index);
            slot.x = grid.gridLeft() + (index % columns) * HoldGrid.SLOT + 1;
            slot.y = grid.gridTop() + (index / columns - scrollRow) * HoldGrid.SLOT + 1;
        }
        for (int index = 0; index < 27; index++) {
            Slot slot = slots.get(holdSlots + index);
            slot.x = grid.playerLeft() + (index % HoldGrid.STRIDE) * HoldGrid.SLOT + 1;
            slot.y = grid.playerTop() + (index / HoldGrid.STRIDE) * HoldGrid.SLOT + 1;
        }
        for (int index = 0; index < HoldGrid.STRIDE; index++) {
            Slot slot = slots.get(holdSlots + 27 + index);
            slot.x = grid.playerLeft() + index * HoldGrid.SLOT + 1;
            slot.y = grid.hotbarTop() + 1;
        }
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return hold.stillValid(player);
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);
        hold.stopOpen(player);
    }

    @NotNull
    @Override
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack inSlot = slot.getItem();
        ItemStack original = inSlot.copy();
        // Hold range includes off-window rows; shift-click still reaches them.
        if (index < holdSlots) {
            if (!moveItemStackTo(inSlot, holdSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(inSlot, 0, holdSlots, false)) {
            return ItemStack.EMPTY;
        }
        if (inSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    public class WindowSlot extends Slot {

        private final int held;

        WindowSlot(Container container, int index) {
            super(container, index, 0, 0);
            this.held = index;
        }

        public int row() {
            return held / grid.columns();
        }

        @Override
        public boolean isActive() {
            int row = row();
            return row >= scrollRow && row < scrollRow + grid.windowRows();
        }

    }

}
