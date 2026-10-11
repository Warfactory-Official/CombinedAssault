package com.wf.combinedassault.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import com.wf.combinedassault.storage.HoldGrid;
import com.wf.combinedassault.storage.VehicleStorageMenu;

/**
 * Hold panel drawn flat, not blitted: vanilla's chest texture caps at 54 slots, a hold is any size.
 * Overflow rows scroll, not paged. Palette matches CombinedAssault's other screens' shared stylesheet.
 */
public class VehicleStorageScreen extends AbstractContainerScreen<VehicleStorageMenu> {

    private static final int SURFACE = 0xCC48494A;

    private static final int HEADER = 0xD1313233;

    private static final int LIGHT = 0xD6E6E8EB;

    private static final int TEXT = 0xFFE6E8EB;
    private static final int MUTED = 0xFF9DA0A4;

    private static final int WELL_EDGE = 0x30000000;
    private static final int WELL = 0x50000000;

    private static final int BLANK = 0x1AFFFFFF;

    private static final int FOOTER = 0xC73A3B3D;

    private static final int OUTLINE = 0x33FFFFFF;

    private static final int MIN_KNOB = 12;

    private int filled;
    private boolean draggingBar;

    public VehicleStorageScreen(VehicleStorageMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        HoldGrid grid = HoldGrid.fit(menu.holdSlots(), width, height);
        this.imageWidth = grid.width();
        this.imageHeight = grid.height();
        this.titleLabelX = HoldGrid.PAD;
        this.titleLabelY = (HoldGrid.TITLE - font.lineHeight) / 2 + 1;
        this.inventoryLabelX = grid.playerLeft();
        this.inventoryLabelY = grid.playerTop() - font.lineHeight - 3;
        super.init();
        menu.layout(grid);
        this.filled = menu.filledSlots();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // filledSlots() is O(holdSlots); tick-rate keeps this readout off the render path.
        this.filled = menu.filledSlots();
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        HoldGrid grid = menu.grid();
        int left = leftPos;
        int top = topPos;

        graphics.fill(left, top, left + imageWidth, top + imageHeight, SURFACE);
        graphics.fill(left, top, left + imageWidth, top + HoldGrid.TITLE, HEADER);
        graphics.fill(left, top + grid.gridBottom(), left + imageWidth, top + imageHeight, FOOTER);
        outline(graphics, left, top, imageWidth, imageHeight);

        int columns = grid.columns();
        int first = menu.scrollRow() * columns;
        for (int row = 0; row < grid.windowRows(); row++) {
            for (int column = 0; column < columns; column++) {
                well(graphics, left + grid.gridLeft() + column * HoldGrid.SLOT,
                        top + grid.gridTop() + row * HoldGrid.SLOT,
                        first + row * columns + column < menu.holdSlots());
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < HoldGrid.STRIDE; column++) {
                well(graphics, left + grid.playerLeft() + column * HoldGrid.SLOT,
                        top + grid.playerTop() + row * HoldGrid.SLOT, true);
            }
        }
        for (int column = 0; column < HoldGrid.STRIDE; column++) {
            well(graphics, left + grid.playerLeft() + column * HoldGrid.SLOT, top + grid.hotbarTop(), true);
        }
        scrollbar(graphics, mouseX, mouseY);
    }

    private void well(GuiGraphics graphics, int x, int y, boolean real) {
        if (!real) {
            graphics.fill(x + 1, y + 1, x + HoldGrid.SLOT - 1, y + HoldGrid.SLOT - 1, BLANK);
            return;
        }
        graphics.fill(x, y, x + HoldGrid.SLOT, y + HoldGrid.SLOT, WELL_EDGE);
        graphics.fill(x + 1, y + 1, x + HoldGrid.SLOT - 1, y + HoldGrid.SLOT - 1, WELL);
    }

    private void outline(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + 1, OUTLINE);
        graphics.fill(x, y + height - 1, x + width, y + height, OUTLINE);
        graphics.fill(x, y, x + 1, y + height, OUTLINE);
        graphics.fill(x + width - 1, y, x + width, y + height, OUTLINE);
    }

    private void scrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        HoldGrid grid = menu.grid();
        if (!grid.scrollable()) {
            return;
        }
        int x = leftPos + grid.barLeft();
        int y = topPos + grid.gridTop();
        graphics.fill(x, y, x + HoldGrid.BAR, y + grid.windowHeight(), WELL);
        int knob = knob(grid);
        int offset = offsetOf(grid, knob);
        boolean lit = draggingBar || onBar(mouseX, mouseY);
        graphics.fill(x, y + offset, x + HoldGrid.BAR, y + offset + knob, lit ? TEXT : LIGHT);
    }

    private int knob(HoldGrid grid) {
        int track = grid.windowHeight();
        return Mth.clamp(track * grid.windowRows() / Math.max(1, grid.rows()), MIN_KNOB, track);
    }

    private int offsetOf(HoldGrid grid, int knob) {
        int travel = grid.windowHeight() - knob;
        if (travel <= 0 || grid.maxScroll() == 0) {
            return 0;
        }
        return Math.round((float) travel * menu.scrollRow() / grid.maxScroll());
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        HoldGrid grid = menu.grid();
        Component count = Component.translatable("gui.combinedassault.storage.count", filled, menu.holdSlots());
        int countX = imageWidth - HoldGrid.PAD - font.width(count);
        graphics.drawString(font, count, countX, titleLabelY, MUTED, false);
        graphics.drawString(font, clip(title, countX - titleLabelX - 6), titleLabelX, titleLabelY, TEXT, false);
        graphics.drawString(font, playerInventoryTitle, grid.playerLeft(), inventoryLabelY, TEXT, false);
    }

    private Component clip(Component text, int room) {
        if (room <= 0 || font.width(text) <= room) {
            return text;
        }
        String tail = "...";
        return Component.literal(font.plainSubstrByWidth(text.getString(),
                Math.max(0, room - font.width(tail))) + tail);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (menu.grid().scrollable() && scrollY != 0) {
            int step = hasShiftDown() ? menu.grid().windowRows() : 1;
            menu.scrollTo(menu.scrollRow() - (int) Math.signum(scrollY) * step);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && onBar(mouseX, mouseY)) {
            draggingBar = true;
            dragTo(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBar) {
            // Any button ends the drag; letting the release fall through hands it to the slot
            // logic underneath, which would drop whatever the cursor is carrying.
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean onBar(double mouseX, double mouseY) {
        HoldGrid grid = menu.grid();
        if (!grid.scrollable()) {
            return false;
        }
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        return x >= grid.barLeft() && x < grid.barLeft() + HoldGrid.BAR
                && y >= grid.gridTop() && y < grid.gridTop() + grid.windowHeight();
    }

    private void dragTo(double mouseY) {
        HoldGrid grid = menu.grid();
        int knob = knob(grid);
        int travel = grid.windowHeight() - knob;
        if (travel <= 0) {
            menu.scrollTo(0);
            return;
        }
        double along = mouseY - topPos - grid.gridTop() - knob / 2.0;
        menu.scrollTo(Math.round((float) (along / travel * grid.maxScroll())));
    }

}
