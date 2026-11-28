package com.wf.combinedassault.screen;

import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;

/** Ore badge: 2 edge, {@code back} fill, white {@code px} text, padding 3 x 8, min height 24; {@code maxWidth} clips. */
public record Badge(Ore.Str text, int back, float px, String maxWidth) implements WidgetDef {
    public Badge(Ore.Str text, int back, float px) {
        this(text, back, px, "0");
    }

    @Override
    public Widget create(Binder binder) {
        Widget label = binder.child(Label.plain(text, Ore.WHITE, px).clipped(maxWidth));
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                label.measure(ev);
                width = label.width() + 20;
                height = Math.max(24, label.height() + 10);
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                OreDraw.fill(ev.canvas, x, y, width, height, back);
                OreDraw.frame(ev.canvas, x, y, width, height, 2, Ore.EDGE);
                label.draw(ev, x + 10, y + (height - label.height()) / 2, label.width(), label.height());
            }
        };
    }
}
