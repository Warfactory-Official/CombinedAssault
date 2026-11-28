package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;

/**
 * Ore card: 3 edge (top = {@code accent}), 2 bevel, 51 header (display 13 px title, {@code badge} right) + 3 rule, body
 * at {@code pad}. Body box = {@link #bodyW} x {@link #bodyH}.
 */
public record Panel(String w, String h, int accent, Ore.Str title, WidgetDef badge, float pad, WidgetDef body)
        implements WidgetDef {
    static final float EDGE = 3;
    static final float HEADER = 51;
    static final float TOP = EDGE + HEADER + EDGE;

    public static String bodyW(String w, float pad) {
        return "(" + w + " - " + (EDGE * 2 + pad * 2) + ")";
    }

    public static String bodyH(String h, float pad) {
        return "(" + h + " - " + (TOP + EDGE + pad * 2) + ")";
    }

    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        Widget heading = binder.child(Label.display(title, Ore.INK, 13));
        Widget tag = badge == null ? null : binder.child(badge);
        Widget content = body == null ? null : binder.child(body);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = Math.max(0, ph.getFloat(ev));
                heading.measure(ev);
                if (tag != null) {
                    tag.measure(ev);
                }
                if (content != null) {
                    content.measure(ev);
                }
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas c = ev.canvas;
                float iw = width - EDGE * 2;
                OreDraw.fill(c, x + EDGE, y + EDGE, iw, height - EDGE * 2, Ore.SURFACE);
                OreDraw.insetTopLeft(c, x + EDGE, y + EDGE, iw, height - EDGE * 2, 2, Ore.EDGE_LIGHT);
                OreDraw.insetBottomRight(c, x + EDGE, y + EDGE, iw, height - EDGE * 2, 2, Ore.EDGE_DARK);
                OreDraw.fill(c, x + EDGE, y + EDGE, iw, HEADER, Ore.SURFACE_DEEP);
                OreDraw.fill(c, x + EDGE, y + EDGE + HEADER, iw, EDGE, Ore.EDGE);
                OreDraw.frame(c, x, y, width, height, EDGE, Ore.EDGE);
                OreDraw.fill(c, x, y, width, EDGE, accent);
                float mid = y + EDGE + HEADER / 2;
                heading.draw(ev, x + EDGE + 16, mid - heading.height() / 2, heading.width(), heading.height());
                if (tag != null) {
                    tag.draw(ev, x + width - EDGE - 16 - tag.width(), mid - tag.height() / 2, tag.width(), tag.height());
                }
                if (content != null) {
                    content.draw(ev, x + EDGE + pad, y + TOP + pad, content.width(), content.height());
                }
            }
        };
    }
}
