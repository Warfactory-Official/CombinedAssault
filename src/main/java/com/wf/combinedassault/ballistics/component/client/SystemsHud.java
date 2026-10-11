package com.wf.combinedassault.ballistics.component.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;

/** Driver HUD panel; {@link #grid} also drives the probe's cell display. */
public final class SystemsHud {

    public static final int COLUMNS = 3;
    public static final int CELL_W = 30;
    public static final int CELL_H = 11;
    public static final int GAP = 2;
    private static final int WIDTH = 150;
    private static final int BOTTOM_MARGIN = 6;
    private static final int BAR_H = 3;
    private static final float CELL_TEXT_SCALE = 0.6f;

    private SystemsHud() {
    }

    public static int colour(ComponentState state) {
        return switch (state) {
            case PRISTINE -> 0xFFE6E6E6;
            case WORN -> 0xFFE8D060;
            case DAMAGED -> 0xFFE08830;
            case BROKEN -> 0xFFB82820;
        };
    }

    public static int gridWidth() {
        return COLUMNS * CELL_W + (COLUMNS - 1) * GAP;
    }

    public static int gridHeight(int cells) {
        int rows = (cells + COLUMNS - 1) / COLUMNS;
        return rows * CELL_H + Math.max(0, rows - 1) * GAP;
    }

    public static void grid(GuiGraphics graphics, List<VehicleSystems.Cell> cells, int x, int y) {
        Font font = Minecraft.getInstance().font;
        PoseStack pose = graphics.pose();
        for (int i = 0; i < cells.size(); i++) {
            VehicleSystems.Cell cell = cells.get(i);
            int cx = x + (i % COLUMNS) * (CELL_W + GAP);
            int cy = y + (i / COLUMNS) * (CELL_H + GAP);
            ComponentState state = cell.state();
            graphics.fill(cx, cy, cx + CELL_W, cy + CELL_H, colour(state));
            pose.pushPose();
            pose.translate(cx + CELL_W / 2f, cy + (CELL_H - font.lineHeight * CELL_TEXT_SCALE) / 2f + 0.5f, 0);
            pose.scale(CELL_TEXT_SCALE, CELL_TEXT_SCALE, 1);
            String label = cell.label();
            graphics.drawString(font, label, -font.width(label) / 2, 0,
                    state == ComponentState.BROKEN ? 0xFFFFFFFF : 0xFF202020, false);
            pose.popPose();
        }
    }

    /** @param extra right-column lines under the speed (fuel, power) */
    public static void render(GuiGraphics graphics, AbstractVehicle vehicle, Component name, int nameColour,
                              List<Component> extra, List<Integer> extraColours) {
        Font font = Minecraft.getInstance().font;
        List<VehicleSystems.Cell> cells = VehicleSystems.cells(vehicle);
        int x0 = graphics.guiWidth() / 2 - WIDTH / 2;
        int gridTop = graphics.guiHeight() - BOTTOM_MARGIN - gridHeight(cells.size());
        int barTop = gridTop - 3 - BAR_H;
        graphics.drawString(font, name, x0, barTop - font.lineHeight - 1, nameColour, true);

        float hull = vehicle.getMaxHealth() <= 0 ? 1 : Math.max(0, vehicle.getHealth() / vehicle.getMaxHealth());
        graphics.fill(x0, barTop, x0 + WIDTH, barTop + BAR_H, 0xA0101014);
        graphics.fill(x0, barTop, x0 + Math.round(WIDTH * hull), barTop + BAR_H,
                colour(ComponentState.of(hull)) | 0xFF000000);

        grid(graphics, cells, x0, gridTop);

        int right = x0 + WIDTH;
        double speed = vehicle.getDeltaMovement().length() * 20 * 3.6;
        String kmh = String.format(java.util.Locale.ROOT, "%.0f km/h", speed);
        graphics.drawString(font, kmh, right - font.width(kmh), gridTop, 0xFFFFFFFF, true);
        PoseStack pose = graphics.pose();
        float scale = 0.5f;
        for (int i = 0; i < extra.size(); i++) {
            pose.pushPose();
            pose.translate(right, gridTop + font.lineHeight + 1 + i * (font.lineHeight * scale + 1), 0);
            pose.scale(scale, scale, 1);
            graphics.drawString(font, extra.get(i), -font.width(extra.get(i)), 0, extraColours.get(i), true);
            pose.popPose();
        }
    }
}
