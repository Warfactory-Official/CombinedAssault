package com.wf.combinedassault.client.hud.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.combinedassault.client.hud.HudCache;
import com.wf.combinedassault.vehicle.control.AmmoSelect;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.RoundCount;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Round kinds of {@link AmmoSelect}'s weapon, above the weapon card: icon, variant, stores count; cursor row lit,
 * overflowing name scrolls sideways. {@link #ROWS} visible, window follows the cursor. Card units (130 wide).
 */
public record AmmoList() implements WidgetDef {
    private static final float W = 130;
    private static final float ROW_H = 14;
    private static final int ROWS = 5;
    private static final float PAD = 3;
    private static final float ICON = 13;
    private static final float NAME_X = PAD + 4 + ICON + 3;
    private static final float COUNT_W = 26;
    /** Window slide time constant, s. */
    private static final double SLIDE_S = 0.06;
    /** Marquee: rest at each end, s; travel, units/s. */
    private static final double MARQUEE_REST_S = 0.8;
    private static final double MARQUEE_SPEED = 24;
    private static final int BACK = 0xCC0A1408;
    private static final int BORDER = 0xFF2A7A3A;
    private static final int LIT = 0x40FFFFFF;
    private static final int PICKED = 0xFFFF8C00;
    private static final int TEXT = 0xFFD8F8E0;
    private static final int TEXT_DIM = 0xFF8AB896;
    private static final int EMPTY = 0xFFFF6040;

    @Override
    public Widget create(Binder binder) {
        return new Widget() {
            private float top;
            private List<RoundCount> options = List.of();
            private float alpha;

            @Override
            public void measure(UiEval ev) {
                AbstractVehicleWeapon<?> weapon = AmmoSelect.weapon();
                alpha = AmmoSelect.alpha();
                options = weapon == null || alpha <= 0 ? List.of() : weapon.roundOptions();
                width = options.isEmpty() ? 0 : W;
                height = options.isEmpty() ? 0 : Math.min(options.size(), ROWS) * ROW_H + PAD * 2;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                if (options.isEmpty()) {
                    return;
                }
                AbstractVehicleWeapon<?> weapon = AmmoSelect.weapon();
                int cursor = Math.min(AmmoSelect.cursor(), options.size() - 1);
                int picked = AmmoSelect.selectedIndex(weapon, options);
                int shown = Math.min(options.size(), ROWS);
                float want = Mth.clamp(cursor - ROWS / 2, 0, options.size() - shown);
                top += (float) ((want - top) * (1 - Math.exp(-ev.dt() / SLIDE_S)));
                if (Math.abs(want - top) < 0.01f) {
                    top = want;
                }
                Canvas canvas = ev.canvas;
                canvas.fill(x, y, x + W, y + height, fade(BACK));
                canvas.frame(x, y, x + W, y + height, 1, fade(BORDER));
                float rowsTop = y + PAD;
                canvas.pushClip(x, rowsTop, x + W, rowsTop + shown * ROW_H);
                int first = Math.max(0, (int) Math.floor(top));
                int last = Math.min(options.size() - 1, (int) Math.ceil(top) + shown - 1);
                for (int i = first; i <= last; i++) {
                    float rowY = rowsTop + (i - top) * ROW_H;
                    RoundCount option = options.get(i);
                    if (i == cursor) {
                        canvas.fill(x + 1, rowY, x + W - 1, rowY + ROW_H, fade(LIT));
                    }
                    if (i == picked) {
                        canvas.fill(x + PAD, rowY + 2, x + PAD + 2, rowY + ROW_H - 2, fade(PICKED));
                    }
                    String count = option.count() == RoundCount.UNLIMITED ? "∞" : "x" + option.count();
                    canvas.textRight(count, x + W - PAD, rowY + 3, fade(option.count() == 0 ? EMPTY : TEXT_DIM), true);
                    drawName(canvas, label(option.round()), x + NAME_X, rowY + 3, x + W - PAD - COUNT_W,
                            i == cursor, fade(i == cursor ? TEXT : TEXT_DIM));
                }
                canvas.popClip();
                if (ev.graphics != null) {
                    icons(canvas, ev.graphics, x + PAD + 4, rowsTop, first, last, shown);
                }
            }

            private void drawName(Canvas canvas, String name, float x, float y, float right, boolean lit, int argb) {
                float over = canvas.width(name) - (right - x);
                float shift = 0;
                if (over > 0 && lit) {
                    double travel = over / MARQUEE_SPEED;
                    double cycle = 2 * (MARQUEE_REST_S + travel);
                    double t = ((System.currentTimeMillis() - AmmoSelect.changedAt()) / 1000.0) % cycle;
                    double phase = t < MARQUEE_REST_S ? 0
                            : t < MARQUEE_REST_S + travel ? (t - MARQUEE_REST_S) / travel
                            : t < 2 * MARQUEE_REST_S + travel ? 1
                            : 1 - (t - 2 * MARQUEE_REST_S - travel) / travel;
                    shift = (float) (over * phase);
                }
                canvas.pushClip(x, y - 1, right, y + ROW_H - 1);
                canvas.text(name, x - shift, y, argb, true);
                canvas.popClip();
            }

            /** Vanilla items ignore canvas clips: rows sliding past the window edge fade instead. */
            private void icons(Canvas canvas, GuiGraphics graphics, float x, float rowsTop, int first, int last, int shown) {
                canvas.suspend();
                PoseStack pose = graphics.pose();
                pose.pushPose();
                canvas.applyTo(pose);
                for (int i = first; i <= last; i++) {
                    ItemStack round = options.get(i).round();
                    float slot = i - top;
                    float edge = Math.min(slot + 1, shown - slot);
                    if (round.isEmpty() || edge <= 0) {
                        continue;
                    }
                    graphics.setColor(1, 1, 1, alpha * Math.min(1, edge));
                    pose.pushPose();
                    pose.translate(x, rowsTop + slot * ROW_H + (ROW_H - ICON) / 2, 0);
                    pose.scale(ICON / 16, ICON / 16, 1);
                    graphics.renderItem(round, 0, 0);
                    pose.popPose();
                }
                graphics.setColor(1, 1, 1, 1);
                graphics.flush();
                pose.popPose();
                canvas.resume();
            }

            private int fade(int argb) {
                return Math.round((argb >>> 24) * alpha) << 24 | argb & 0xFFFFFF;
            }
        };
    }

    private static String label(ItemStack round) {
        return round.isEmpty() ? Component.translatable("ui.ammo.any").getString() : HudCache.roundLabel(round);
    }
}
