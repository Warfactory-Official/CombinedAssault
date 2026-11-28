package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.TextBox;
import com.wf.wflib.ui.api.widget.Point;
import com.wf.wflib.ui.api.widget.TextBoxField;
import com.wf.wflib.ui.api.widget.Transform;

/**
 * Ore {@code .form-input} around a wflib {@link TextBoxField}: 3 edge (focus white, error red), deep fill, 5 px top
 * shade; text at {@code px} via a scale transform.
 */
public record Field(TextBox box, String placeholder, String w, float h, float px, int ink) implements WidgetDef {
    private static final int ERROR = 0xFFD45B50;

    @Override
    public Widget create(Binder binder) {
        float s = px / Label.EM;
        float inner = (h - 6) / s;
        TextBoxField field = new TextBoxField(box, placeholder, "((" + w + ") - 10) / " + s, inner,
                new TextBoxField.Colours(0, 0, 0, 0, ink, 0xFF8B8D91, 0x8058A6D2));
        NumProp pw = binder.num(w);
        Widget node = binder.child(new Transform("0", "0", "0", Float.toString(s), Point.TOP_LEFT, field));
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = h;
                node.measure(ev);
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas c = ev.canvas;
                OreDraw.fill(c, x + 3, y + 3, width - 6, height - 6, Ore.SURFACE_DEEP);
                OreDraw.fill(c, x + 3, y + 3, width - 6, 5, Ore.FIELD_SHADOW);
                OreDraw.frame(c, x, y, width, height, 3, box.error() ? ERROR : box.focused() ? Ore.WHITE : Ore.EDGE);
                node.draw(ev, x + 5, y + 3, node.width(), node.height());
            }
        };
    }
}
