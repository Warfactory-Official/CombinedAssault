package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.Interactive;
import com.wf.wflib.ui.api.input.UiAction;
import com.wf.wflib.ui.api.input.UiInput;

/**
 * Ore {@code .button}: 3 edge, inset 3 highlight / 3+9 shade / 6 tone shadow; pressed => flat bevel, label 2 lower;
 * disabled => grey. Display-font label, 2 px drop shadow. {@code w} "0" => label + 32.
 */
public record OreButton(UiAction action, Ore.Flag enabled, Ore.Tone tone, Ore.Str label, String w, float h, float px)
        implements WidgetDef {
    private static final int SHADE = 0x1F000000;
    private static final int PRESS_HI = 0x2EFFFFFF;
    private static final int PRESS_LO = 0x2E000000;
    private static final int TEXT_SHADOW = 0x59000000;
    private static final int OFF_EDGE = 0xFF313233;
    private static final int OFF_FILL = 0xFF55575A;
    private static final int OFF_SHADOW = 0xFF3B3C3E;
    private static final int OFF_HI = 0xFF696B6E;
    private static final int OFF_INK = 0xFF8B8D91;

    @Override
    public Widget create(Binder binder) {
        return new Node(binder.num(w), binder.itemSlot());
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final int itemSlot;
        private final Label.Line line = new Label.Line(Ore.DISPLAY);
        private float textW;

        Node(NumProp pw, int itemSlot) {
            this.pw = pw;
            this.itemSlot = itemSlot;
        }

        @Override
        public void measure(UiEval ev) {
            textW = line.width(ev.canvas, label.get(ev.item(itemSlot))) * px / Label.EM;
            float fixed = pw.getFloat(ev);
            width = fixed > 0 ? fixed : textW + 32;
            height = h;
        }

        @Override
        public void draw(UiEval ev, float x, float y, float bw, float bh) {
            Canvas c = ev.canvas;
            Object item = ev.item(itemSlot);
            boolean on = enabled.get(item);
            UiInput input = ev.input;
            boolean hover = false;
            boolean down = false;
            if (input != null && on) {
                input.hit(x, y, x + width, y + height, this, item);
                hover = input.hovered(this, item);
                down = hover && input.captured(this, item);
            }
            float ix = x + 3;
            float iy = y + 3;
            float iw = width - 6;
            float ih = height - 6;
            float lift;
            if (!on) {
                OreDraw.fill(c, ix, iy, iw, ih, OFF_FILL);
                OreDraw.fill(c, ix, iy + ih - 5, iw, 5, OFF_SHADOW);
                OreDraw.insetTopLeft(c, ix, iy, iw, ih, 2, OFF_HI);
                OreDraw.frame(c, x, y, width, height, 3, OFF_EDGE);
                lift = 3;
            } else if (down) {
                OreDraw.fill(c, ix, iy, iw, ih, tone.hover);
                OreDraw.insetTopLeft(c, ix, iy, iw, ih, 3, PRESS_HI);
                OreDraw.insetBottomRight(c, ix, iy, iw, ih, 3, PRESS_LO);
                OreDraw.frame(c, x, y, width, height, 3, Ore.EDGE);
                lift = 1;
            } else {
                OreDraw.fill(c, ix, iy, iw, ih, hover ? tone.hover : tone.base);
                OreDraw.fill(c, ix + iw - 3, iy, 3, ih, SHADE);
                OreDraw.fill(c, ix, iy + ih - 9, iw - 3, 9, SHADE);
                OreDraw.insetTopLeft(c, ix, iy, iw, ih, 3, tone.hi);
                OreDraw.fill(c, ix, iy + ih - 6, iw, 6, tone.shadow);
                OreDraw.frame(c, x, y, width, height, 3, Ore.EDGE);
                lift = 3;
            }
            if (line.text == null || line.text.isEmpty()) {
                return;
            }
            float tx = x + (width - textW) / 2;
            float ty = y + (height - px) / 2 - lift;
            if (on) {
                c.push().translate(2, 2);
                drawText(ev, tx, ty, TEXT_SHADOW);
                c.pop();
                drawText(ev, tx, ty, Ore.WHITE);
            } else {
                drawText(ev, tx, ty, OFF_INK);
            }
        }

        private void drawText(UiEval ev, float x, float y, int argb) {
            Canvas c = ev.canvas;
            float s = px / Label.EM;
            c.push().translate(x, y).scale(s);
            line.draw(c, 0, 0, argb);
            c.pop();
        }

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            if (inside && enabled.get(item)) {
                OreScreen.click();
                action.run(item);
            }
        }
    }
}
