package com.wf.combinedassault.ballistics.component.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.wf.gemrender.ui.Canvas;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.VehicleSystems;

import java.util.List;

/** Systems cell colours and the probe's cell grid. */
public final class SystemsHud {

    public static final int COLUMNS = 3;
    public static final int CELL_W = 30;
    public static final int CELL_H = 11;
    public static final int GAP = 2;
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

    public static void grid(Canvas canvas, List<VehicleSystems.Cell> cells, int x, int y) {
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < cells.size(); i++) {
            VehicleSystems.Cell cell = cells.get(i);
            int cx = x + (i % COLUMNS) * (CELL_W + GAP);
            int cy = y + (i / COLUMNS) * (CELL_H + GAP);
            ComponentState state = cell.state();
            canvas.fill(cx, cy, cx + CELL_W, cy + CELL_H, colour(state));
            canvas.push().translate(cx + CELL_W / 2f, cy + (CELL_H - font.lineHeight * CELL_TEXT_SCALE) / 2f + 0.5f)
                    .scale(CELL_TEXT_SCALE);
            String label = cell.label();
            canvas.text(label, -font.width(label) / 2, 0, state == ComponentState.BROKEN ? 0xFFFFFFFF : 0xFF202020, false);
            canvas.pop();
        }
    }
}
