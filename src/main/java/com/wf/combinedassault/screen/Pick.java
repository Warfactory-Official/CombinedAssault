package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.Interactive;
import com.wf.wflib.ui.api.input.UiAction;
import com.wf.wflib.ui.api.input.UiInput;

/** Selectable row / tile: fill + {@code edge}-wide border per state (normal, hover, active); child at (padX, padY). */
public record Pick(UiAction action, Ore.Flag active, String w, String h, Look look, float padX, float padY,
                   WidgetDef child) implements WidgetDef {
    /** ARGB per state; {@code rule} > 0 => bottom rule instead of a frame (list-group-item). */
    public record Look(int back, int hover, int on, int edgeBack, int edgeHover, int edgeOn, float edge, float rule) {
        /** weapon / display entry. */
        public static final Look ENTRY = new Look(Ore.ROW, Ore.ROW_HOVER, Ore.ROW_ACTIVE, 0, Ore.ROW_HOVER_EDGE,
                Ore.GOLD, 2, 0);
        /** list-group-item. */
        public static final Look ITEM = new Look(Ore.LIST_ITEM, Ore.LIST_ITEM_HOVER, Ore.GREEN, Ore.EDGE_DARK,
                Ore.EDGE_DARK, Ore.EDGE_DARK, 0, 2);
        /** decoration tile. */
        public static final Look TILE = new Look(0xCC3B3C3E, 0xE04B4C4F, 0xFF514A35, 0xFF55575A, 0xFFDDD3B1,
                Ore.GOLD, 2, 0);
    }

    @Override
    public Widget create(Binder binder) {
        return new Node(binder.num(w), binder.num(h), binder.itemSlot(), child == null ? null : binder.child(child));
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final NumProp ph;
        private final int itemSlot;
        private final Widget node;

        Node(NumProp pw, NumProp ph, int itemSlot, Widget node) {
            this.pw = pw;
            this.ph = ph;
            this.itemSlot = itemSlot;
            this.node = node;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = Math.max(0, ph.getFloat(ev));
            if (node != null) {
                node.measure(ev);
            }
        }

        @Override
        public void draw(UiEval ev, float x, float y, float bw, float bh) {
            Canvas c = ev.canvas;
            Object item = ev.item(itemSlot);
            UiInput input = ev.input;
            boolean hover = false;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, item);
                hover = input.hovered(this, item);
            }
            boolean on = active.get(item);
            OreDraw.fill(c, x, y, width, height, on ? look.on : hover ? look.hover : look.back);
            int edge = on ? look.edgeOn : hover ? look.edgeHover : look.edgeBack;
            if (look.rule > 0) {
                OreDraw.fill(c, x, y + height - look.rule, width, look.rule, edge);
            } else {
                OreDraw.frame(c, x, y, width, height, look.edge, edge);
            }
            if (node != null) {
                node.draw(ev, x + padX, y + padY, node.width(), node.height());
            }
        }

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            if (inside) {
                action.run(item);
            }
        }
    }
}
