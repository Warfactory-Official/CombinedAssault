package com.wf.combinedassault.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * Sunken 3D viewport ({@code axes}: centre cross). Model area = box inset {@link #INSET}; its GUI-px rect goes to
 * {@code port} (current screen's) each frame (mouse routing in {@link OreScreen}), then {@code painter} draws vanilla under a scissor.
 */
public record ModelPort(Supplier<Port> port, Painter painter, String w, String h, boolean axes) implements WidgetDef {
    static final float INSET = 7;

    /** Vanilla drawing in GUI px; canvas suspended, scissor = {@code port}. */
    @FunctionalInterface
    public interface Painter {
        void paint(GuiGraphics graphics, Port port);
    }

    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        return new Widget() {
            private final PoseStack scratch = new PoseStack();
            private final Vector4f corner = new Vector4f();

            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = Math.max(0, ph.getFloat(ev));
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas c = ev.canvas;
                OreDraw.viewport(c, x, y, width, height);
                if (axes) {
                    OreDraw.fill(c, x + INSET, y + height / 2, width - INSET * 2, 1, Ore.AXIS);
                    OreDraw.fill(c, x + width / 2, y + INSET, 1, height - INSET * 2, Ore.AXIS);
                }
                GuiGraphics graphics = ev.graphics;
                if (graphics == null || width <= INSET * 2 || height <= INSET * 2) {
                    return;
                }
                scratch.setIdentity();
                c.applyTo(scratch);
                corner.set(x + INSET, y + INSET, 0, 1).mul(scratch.last().pose());
                float left = corner.x;
                float top = corner.y;
                corner.set(x + width - INSET, y + height - INSET, 0, 1).mul(scratch.last().pose());
                Port target = port.get();
                target.place(left, top, corner.x, corner.y);
                c.suspend();
                graphics.enableScissor((int) Math.floor(left), (int) Math.floor(top), (int) Math.ceil(corner.x),
                        (int) Math.ceil(corner.y));
                try {
                    painter.paint(graphics, target);
                    graphics.flush();
                } finally {
                    graphics.disableScissor();
                    c.resume();
                }
            }
        };
    }
}
