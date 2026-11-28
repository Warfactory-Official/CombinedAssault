package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.Interactive;
import com.wf.wflib.ui.api.input.UiAction;
import com.wf.wflib.ui.api.input.UiInput;

/** Ore {@code .checkbox} (20 px, grey / green, white tick) + label; whole row toggles. */
public record Check(Ore.Flag checked, UiAction toggle, Ore.Str label, float px) implements WidgetDef {
    private static final float SIZE = 20;

    @Override
    public Widget create(Binder binder) {
        return new Node(binder.itemSlot(), binder.child(Label.plain(label, Ore.WHITE, px)));
    }

    private final class Node extends Widget implements Interactive {
        private final int itemSlot;
        private final Widget text;

        Node(int itemSlot, Widget text) {
            this.itemSlot = itemSlot;
            this.text = text;
        }

        @Override
        public void measure(UiEval ev) {
            text.measure(ev);
            width = SIZE + 8 + text.width();
            height = Math.max(SIZE, text.height());
        }

        @Override
        public void draw(UiEval ev, float x, float y, float bw, float bh) {
            Canvas c = ev.canvas;
            Object item = ev.item(itemSlot);
            UiInput input = ev.input;
            boolean hover = false;
            boolean down = false;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, item);
                hover = input.hovered(this, item);
                down = hover && input.captured(this, item);
            }
            boolean on = checked.get(item);
            float by = y + (height - SIZE) / 2;
            int back = on ? down ? 0xFF1D4D13 : hover ? 0xFF2A641C : Ore.GREEN
                    : down ? 0xFF58585A : hover ? 0xFFB1B2B5 : 0xFF8C8D90;
            OreDraw.fill(c, x + 2, by + 2, SIZE - 4, SIZE - 4, back);
            OreDraw.insetTopLeft(c, x + 2, by + 2, SIZE - 4, SIZE - 4, 2, 0x33FFFFFF);
            OreDraw.insetBottomRight(c, x + 2, by + 2, SIZE - 4, SIZE - 4, 2, 0x1AFFFFFF);
            OreDraw.frame(c, x, by, SIZE, SIZE, 2, Ore.EDGE);
            if (on) {
                c.line(x + 5, by + 10, x + 8.5f, by + 14, 3, Ore.WHITE);
                c.line(x + 8.5f, by + 14, x + 15, by + 5, 3, Ore.WHITE);
            }
            text.draw(ev, x + SIZE + 8, y + (height - text.height()) / 2, text.width(), text.height());
        }

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            if (inside) {
                OreScreen.click();
                toggle.run(item);
            }
        }
    }
}
