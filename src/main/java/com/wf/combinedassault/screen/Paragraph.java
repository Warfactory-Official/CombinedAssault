package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.Interactive;
import com.wf.wflib.ui.api.input.UiInput;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Word-wrapped text in {@code w x h}, wheel-scrolled when taller; vanilla line breaking (CJK per glyph). */
public record Paragraph(Ore.Str text, String w, String h, int argb, float px, float lineHeight, Style style)
        implements WidgetDef {

    @Override
    public Widget create(Binder binder) {
        return new Node(binder.num(w), binder.num(h), binder.itemSlot());
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final NumProp ph;
        private final int itemSlot;
        private String wrapped;
        private float wrappedWidth = -1;
        private int font = Integer.MIN_VALUE;
        private List<FormattedCharSequence> lines = List.of();
        private float scroll;

        Node(NumProp pw, NumProp ph, int itemSlot) {
            this.pw = pw;
            this.ph = ph;
            this.itemSlot = itemSlot;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = Math.max(0, ph.getFloat(ev));
            String s = text.get(ev.item(itemSlot));
            float scale = px / Label.EM;
            if (s != wrapped && (s == null || !s.equals(wrapped)) || width != wrappedWidth
                    || font != ev.canvas.fontVersion()) {
                wrapped = s;
                wrappedWidth = width;
                font = ev.canvas.fontVersion();
                scroll = 0;
                lines = s == null || s.isEmpty() ? List.of() : Minecraft.getInstance().font.split(
                        Component.literal(s).withStyle(style), Math.max(1, (int) (width / scale)));
            }
        }

        private float content() {
            return lines.size() * lineHeight;
        }

        @Override
        public void draw(UiEval ev, float x, float y, float bw, float bh) {
            Canvas c = ev.canvas;
            float scale = px / Label.EM;
            scroll = Math.max(0, Math.min(scroll, content() - height));
            UiInput input = ev.input;
            if (input != null && content() > height) {
                input.hit(x, y, x + width, y + height, this, null);
            }
            c.pushClip(x, y, x + width, y + height);
            float lead = (lineHeight - px) / 2;
            for (int i = (int) (scroll / lineHeight); i < lines.size(); i++) {
                float top = y + i * lineHeight - scroll + lead;
                if (top >= y + height) {
                    break;
                }
                c.push().translate(x, top).scale(scale);
                c.text(lines.get(i), 0, 0, argb, false, false);
                c.pop();
            }
            c.popClip();
        }

        @Override
        public boolean feedback() {
            return false;
        }

        @Override
        public boolean wheel(Object item, float amount) {
            float before = scroll;
            scroll = Math.max(0, Math.min(scroll - amount * lineHeight * 2, content() - height));
            return scroll != before;
        }
    }
}
