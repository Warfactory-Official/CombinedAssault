package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * One line at {@code px} CSS px; {@code style} null => default font fast path. {@code maxWidth} > 0 => clipped (CSS
 * {@code overflow: hidden}). {@code shadow} != 0 => drawn first at +{@code px / 9}.
 */
public record Label(Ore.Str text, int argb, float px, Style style, String maxWidth, int shadow) implements WidgetDef {
    /** Glyph units per em; vanilla line 9. */
    static final float EM = 9;

    public static Label plain(Ore.Str text, int argb, float px) {
        return new Label(text, argb, px, null, "0", 0);
    }

    public static Label display(Ore.Str text, int argb, float px) {
        return new Label(text, argb, px, Ore.DISPLAY, "0", 0);
    }

    public static Ore.Str of(String constant) {
        return item -> constant;
    }

    public Label clipped(String max) {
        return new Label(text, argb, px, style, max, shadow);
    }

    public Label shadowed(int colour) {
        return new Label(text, argb, px, style, maxWidth, colour);
    }

    public Label styled(Style s) {
        return new Label(text, argb, px, s, maxWidth, shadow);
    }

    @Override
    public Widget create(Binder binder) {
        NumProp max = binder.num(maxWidth);
        int itemSlot = binder.itemSlot();
        return new Widget() {
            private final Line line = new Line(style);
            private float scale;
            private float clip;

            @Override
            public void measure(UiEval ev) {
                scale = px / EM;
                clip = max.getFloat(ev);
                float natural = line.width(ev.canvas, text.get(ev.item(itemSlot))) * scale;
                width = clip > 0 ? Math.min(natural, clip) : natural;
                height = EM * scale;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                if (line.text == null || line.text.isEmpty()) {
                    return;
                }
                Canvas c = ev.canvas;
                if (clip > 0) {
                    c.pushClip(x, y - height, x + clip, y + height * 2);
                }
                c.push().translate(x, y).scale(scale);
                if (shadow != 0) {
                    line.draw(c, 1, 1, shadow);
                }
                line.draw(c, 0, 0, argb);
                c.pop();
                if (clip > 0) {
                    c.popClip();
                }
            }
        };
    }

    /** Cached styled sequence; rebuilt only when the string changes. */
    static final class Line {
        private final Style style;
        String text;
        private FormattedCharSequence seq;
        private float width;
        private int font = Integer.MIN_VALUE;

        Line(Style style) {
            this.style = style;
        }

        float width(Canvas c, String s) {
            if (s != text && (s == null || !s.equals(text)) || font != c.fontVersion()) {
                text = s;
                font = c.fontVersion();
                if (s == null || s.isEmpty()) {
                    seq = null;
                    width = 0;
                } else if (style == null) {
                    seq = null;
                    width = c.width(s);
                } else {
                    seq = FormattedCharSequence.forward(s, style);
                    width = c.width(seq);
                }
            }
            return width;
        }

        void draw(Canvas c, float x, float y, int argb) {
            if (seq == null) {
                c.text(text, x, y, argb, false);
            } else {
                c.text(seq, x, y, argb, false, false);
            }
        }
    }
}
