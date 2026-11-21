package com.wf.combinedassault.hud.widget;

import com.wf.combinedassault.hud.HudCache;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.VehicleTool;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.ItemsRef;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Station weapons as a stack of rows, first at the top ({@code station.weapons}, cursor {@code station.active_index}).
 * The active row sits flush with a lit frame, pointer and ammo count; the others stand back to the right, dim.
 * More than {@link #ROWS}: the window follows the active row. Card units (130 wide).
 */
public record WeaponList() implements WidgetDef {
    private static final float W = 130;
    private static final float ROW_H = 13;
    private static final float GAP = 2;
    private static final int ROWS = 5;
    /** How far back an inactive row stands, units. */
    private static final float SETBACK = 14;
    private static final double SLIDE_S = 0.07;
    private static final int BACK = 0xB0000000;
    private static final int LIT = 0x40FFFFFF;
    private static final int EDGE = 0xFFFFFFFF;
    private static final int EDGE_DIM = 0x33FFFFFF;
    private static final int TEXT = 0xFFF4F4F4;
    private static final int TEXT_DIM = 0xFFB4B4B4;
    private static final int LOW = 0xFFFFAA00;
    private static final int EMPTY = 0xFFFF6040;
    private static final int ON = 0xFF60FF60;
    private static final String[] NUMBERS = new String[1000];

    @Override
    public Widget create(Binder binder) {
        ItemsRef<?> ref = binder.items("station.weapons");
        NumProp activeProp = binder.num("station.active_index");
        return new Widget() {
            private float top;
            private float[] lift = new float[8];

            @Override
            public void measure(UiEval ev) {
                int count = ref.get(ev).size();
                int shown = Math.min(count, ROWS);
                width = count == 0 ? 0 : W;
                height = shown == 0 ? 0 : shown * ROW_H + (shown - 1) * GAP;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                List<?> weapons = ref.get(ev);
                int count = weapons.size();
                if (count == 0) {
                    return;
                }
                if (lift.length < count) {
                    lift = new float[count];
                }
                int active = Mth.clamp((int) activeProp.get(ev), 0, count - 1);
                int shown = Math.min(count, ROWS);
                float want = Mth.clamp(active - (ROWS - 1) / 2f, 0, count - shown);
                float k = (float) (1 - Math.exp(-ev.dt() / SLIDE_S));
                top += (want - top) * k;
                float pitch = ROW_H + GAP;
                Canvas canvas = ev.canvas;
                canvas.pushClip(x - 1, y - 1, x + W + 1, y + shown * pitch);
                int first = Math.max(0, (int) Math.floor(top));
                int last = Math.min(count - 1, (int) Math.ceil(top) + shown - 1);
                for (int i = 0; i < count; i++) {
                    lift[i] += ((i == active ? 1f : 0f) - lift[i]) * k;
                }
                for (int i = first; i <= last; i++) {
                    AbstractVehicleWeapon<?> weapon = (AbstractVehicleWeapon<?>) weapons.get(i);
                    float sel = lift[i];
                    float rowY = y + (i - top) * pitch;
                    float edge = Math.min(Math.min(i - top + 1, shown - (i - top)), 1);
                    if (edge <= 0) {
                        continue;
                    }
                    float rowX = x + (1 - sel) * SETBACK;
                    float fade = (0.5f + 0.5f * sel) * edge;
                    canvas.fill(rowX, rowY, x + W, rowY + ROW_H, scale(BACK, fade));
                    if (sel > 0.01f) {
                        canvas.fill(rowX, rowY, x + W, rowY + ROW_H, scale(LIT, sel * edge));
                    }
                    canvas.frame(rowX, rowY, x + W, rowY + ROW_H, 1, scale(sel > 0.5f ? EDGE : EDGE_DIM, fade));
                    if (sel > 0.01f) {
                        float cy = rowY + ROW_H / 2;
                        canvas.triangle(rowX - 7, cy - 3, rowX - 7, cy + 3, rowX - 2, cy, scale(EDGE, sel * edge));
                    }
                    String name = HudCache.weaponName(weapon);
                    canvas.pushClip(rowX + 3, rowY, x + W - 30, rowY + ROW_H);
                    canvas.text(name, rowX + 4, rowY + 3, scale(sel > 0.5f ? TEXT : TEXT_DIM, edge), true);
                    canvas.popClip();
                    drawStatus(canvas, weapon, x + W - 3, rowY + 3, edge);
                }
                canvas.popClip();
            }

            private void drawStatus(Canvas canvas, AbstractVehicleWeapon<?> weapon, float right, float y, float edge) {
                if (weapon instanceof VehicleTool tool) {
                    boolean on = tool.spinning();
                    canvas.textRight(on ? "ON" : "--", right, y, scale(on ? ON : TEXT_DIM, edge), true);
                    return;
                }
                int ammo = weapon.getRemainAmmo();
                int color = weapon.isReloading() || ammo == 0 ? EMPTY
                        : ammo <= weapon.getMaxCapacity() / 4 ? LOW : TEXT;
                canvas.textRight(number(ammo), right, y, scale(color, edge), true);
            }
        };
    }

    private static String number(int n) {
        if (n < 0 || n >= NUMBERS.length) {
            return Integer.toString(n);
        }
        String s = NUMBERS[n];
        if (s == null) {
            s = Integer.toString(n);
            NUMBERS[n] = s;
        }
        return s;
    }

    private static int scale(int argb, float alpha) {
        return Math.round((argb >>> 24) * Mth.clamp(alpha, 0f, 1f)) << 24 | argb & 0xFFFFFF;
    }
}
