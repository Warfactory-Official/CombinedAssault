package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.Interactive;
import com.wf.wflib.ui.api.input.UiInput;
import com.wf.wflib.ui.api.input.UiSetter;

/**
 * Range input, Apricity look: 3 px track (gold filled, {@link Ore#TRACK} rest), gold marker 6 x 10 pointing right.
 * Press / drag => {@code set} (0..1); {@code done} (nullable) on release.
 */
public record Range(Ore.Num value, UiSetter set, Runnable done, String w, float h) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        return new Node(binder.num(w), binder.itemSlot());
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final int itemSlot;

        Node(NumProp pw, int itemSlot) {
            this.pw = pw;
            this.itemSlot = itemSlot;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = h;
        }

        @Override
        public void draw(UiEval ev, float x, float y, float bw, float bh) {
            Object item = ev.item(itemSlot);
            UiInput input = ev.input;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, item);
            }
            Canvas c = ev.canvas;
            float v = Math.max(0, Math.min(1, value.get(item)));
            float cy = y + height / 2;
            float at = x + v * width;
            OreDraw.fill(c, x, cy - 1.5f, width, 3, Ore.TRACK);
            OreDraw.fill(c, x, cy - 1.5f, at - x, 3, Ore.GOLD);
            c.triangle(at, cy - 5, at, cy + 5, at + 6, cy, Ore.GOLD);
        }

        @Override
        public void press(Object item, float x, float y) {
            drag(item, x, y);
        }

        @Override
        public void drag(Object item, float x, float y) {
            if (width > 0 && !Float.isNaN(x)) {
                set.set(item, Math.max(0, Math.min(1, x / width)));
            }
        }

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            if (done != null) {
                done.run();
            }
        }
    }
}
