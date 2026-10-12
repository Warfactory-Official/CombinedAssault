package com.wf.combinedassault.client.hud.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Range bar 0..{@code max} ({@code w} wide, 5 tall, quarter ticks) with the [{@code near}, {@code far}]
 * window lit and a {@code mark} line; near / far / max figures underneath, each dropped when it would
 * touch its neighbour. Java: label placement depends on measured text.
 */
public record RangeScale(float w, String near, String far, String max, String mark, String track, String scale,
                         String band, String markColor, String label) implements WidgetDef {
    private static final float BAR = 5;
    private static final float LEGEND = 7;
    private static final float GAP = 3;

    @Override
    public Widget create(Binder binder) {
        NumProp nearProp = binder.num(near);
        NumProp farProp = binder.num(far);
        NumProp maxProp = binder.num(max);
        NumProp markProp = binder.num(mark);
        ColorProp trackColour = binder.color(track);
        ColorProp scaleColour = binder.color(scale);
        ColorProp bandColour = binder.color(band);
        ColorProp markColour = binder.color(markColor);
        ColorProp labelColour = binder.color(label);
        return new Widget() {
            private final TextBuffer text = new TextBuffer(16);

            @Override
            public void measure(UiEval ev) {
                width = w;
                height = LEGEND + ev.canvas.lineHeight() - 1;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas canvas = ev.canvas;
                double reach = maxProp.get(ev);
                double nearest = nearProp.get(ev);
                double farthest = farProp.get(ev);
                float right = x + w;
                int scaleArgb = scaleColour.get(ev);
                canvas.fill(x, y, right, y + BAR, trackColour.get(ev));
                for (int quarter = 1; quarter < 4; quarter++) {
                    float at = x + (int) (quarter * w / 4);
                    canvas.fill(at, y, at + 1, y + BAR, scaleArgb);
                }
                if (!(reach > 0) || Double.isNaN(nearest) || Double.isNaN(farthest)) {
                    return;
                }
                double perBlock = w / reach;
                float nearX = x + (int) Math.round(Math.min(nearest, reach) * perBlock);
                float farX = x + (int) Math.round(Math.min(farthest, reach) * perBlock);
                canvas.fill(nearX, y, Math.max(farX, nearX + 1), y + BAR, bandColour.get(ev));
                double laid = markProp.get(ev);
                if (!Double.isNaN(laid)) {
                    float at = Math.max(x, Math.min(right - 1, x + (int) Math.round(laid * perBlock)));
                    canvas.fill(at, y - 2, at + 1, y + BAR + 2, markColour.get(ev));
                }
                float ly = y + LEGEND;
                text.clear().append(Math.round(nearest));
                float nearW = canvas.width(text);
                float nearLabel = Math.max(x, nearX - (int) nearW / 2);
                canvas.text(text, nearLabel, ly, scaleArgb, false);
                text.clear().append(Math.round(farthest));
                float farW = canvas.width(text);
                float farLabel = Math.min(right - farW, farX - (int) farW / 2);
                if (farLabel > nearLabel + nearW + GAP) {
                    canvas.text(text, farLabel, ly, scaleArgb, false);
                }
                text.clear().append(Math.round(reach));
                float reachLabel = right - canvas.width(text);
                if (reachLabel > farLabel + farW + GAP) {
                    canvas.text(text, reachLabel, ly, labelColour.get(ev), false);
                }
            }
        };
    }
}
