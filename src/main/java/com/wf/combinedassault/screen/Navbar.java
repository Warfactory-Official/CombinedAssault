package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.UiAction;

/** Ore navbar across {@link Ore#W}: 52 light band, 6 inset shade, 3 rule; display title; red X when {@code close}. */
public record Navbar(Ore.Str title, float px, UiAction close) implements WidgetDef {
    public static final float HEIGHT = 61;

    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(Ore.W);
        Widget brand = binder.child(Label.display(title, Ore.INK_DARK, px));
        Widget x = close == null ? null
                : binder.child(new OreButton(close, i -> true, Ore.Tone.RED, Label.of("X"), "34", 32, 17));
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = pw.getFloat(ev);
                height = HEIGHT;
                brand.measure(ev);
                if (x != null) {
                    x.measure(ev);
                }
            }

            @Override
            public void draw(UiEval ev, float px0, float py0, float bw, float bh) {
                Canvas c = ev.canvas;
                OreDraw.fill(c, px0, py0, width, 52, Ore.NAV);
                OreDraw.fill(c, px0, py0 + 52, width, 6, Ore.NAV_SHADOW);
                OreDraw.fill(c, px0, py0 + 58, width, 3, Ore.NAV_EDGE);
                brand.draw(ev, px0 + 20, py0 + 26 - brand.height() / 2, brand.width(), brand.height());
                if (x != null) {
                    x.draw(ev, px0 + width - 10 - x.width(), py0 + 26 - x.height() / 2, x.width(), x.height());
                }
            }
        };
    }
}
