package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;

/** Ore {@code .progress}: 2 edge, 2 padding, 3 px top shade; green bar with 4 px shadow + 2 px highlight. */
public record Meter(Ore.Num value, String w, float h) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        int itemSlot = binder.itemSlot();
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = h;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas c = ev.canvas;
                OreDraw.fill(c, x + 2, y + 2, width - 4, height - 4, 0xB8252628);
                OreDraw.fill(c, x + 2, y + 2, width - 4, 3, 0xFF151516);
                OreDraw.frame(c, x, y, width, height, 2, Ore.EDGE);
                float v = Math.max(0, Math.min(1, value.get(ev.item(itemSlot))));
                float bw2 = (width - 8) * v;
                if (bw2 > 0) {
                    float by = y + 4;
                    float bh2 = height - 8;
                    OreDraw.fill(c, x + 4, by, bw2, bh2, Ore.GREEN);
                    OreDraw.fill(c, x + 4, by + bh2 - Math.min(4, bh2), bw2, Math.min(4, bh2), 0xFF1D4D13);
                    OreDraw.insetTopLeft(c, x + 4, by, bw2, bh2, Math.min(2, bh2), 0x33FFFFFF);
                }
            }
        };
    }
}
