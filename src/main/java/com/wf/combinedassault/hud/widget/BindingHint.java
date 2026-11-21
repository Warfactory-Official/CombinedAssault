package com.wf.combinedassault.hud.widget;

import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.Ui;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.widget.Align;
import com.wf.wflib.ui.api.widget.Box;
import com.wf.wflib.ui.api.widget.Point;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Key hint for an action with a wheel gesture ({@code modifier} + wheel) and direct keys. Every cap reads its
 * live mapping; an unbound mapping draws nothing.
 */
public record BindingHint(String condition, String label, String modifier, List<String> keys) implements WidgetDef {

    @Override
    public Widget create(Binder binder) {
        List<WidgetDef> caps = new ArrayList<>();
        caps.add(new Bound(modifier, Ui.row(1.5F, Align.CENTER, cap("{key:" + modifier + "}"), plus(),
                cap("{tr:hud.combinedassault.hint.wheel}"))));
        for (String key : keys) {
            caps.add(new Bound(key, cap("{key:" + key + "}")));
        }
        WidgetDef name = Ui.text(label, "#E8E8E8E0", true, "0.75");
        return binder.child(Ui.when("ui.key_hints && (" + condition + ")",
                Ui.row(3, Align.CENTER, Ui.row(3, Align.CENTER, caps.toArray(WidgetDef[]::new)), name)));
    }

    private static WidgetDef cap(String template) {
        return new Box("#B8101410", "#70E8E8E0", 0.75F, 0, 2.25F, 1.5F, 10.5F, 9, Point.CENTER,
                Ui.text(template, "#FFF2F2EA", false, "0.75"));
    }

    private static WidgetDef plus() {
        return Ui.text("+", "#E8E8E8E0", true, "0.75");
    }

    /** Zero size while the named key mapping is unbound. */
    private record Bound(String mapping, WidgetDef child) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            KeyMapping key = find(mapping);
            Widget inner = binder.child(child);
            return new Widget() {
                private boolean shown;

                @Override
                public void measure(UiEval ev) {
                    shown = !key.isUnbound();
                    if (shown) {
                        inner.measure(ev);
                    }
                    width = shown ? inner.width() : 0;
                    height = shown ? inner.height() : 0;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    if (shown) {
                        inner.draw(ev, x, y, w, h);
                    }
                }
            };
        }

        private static KeyMapping find(String name) {
            for (KeyMapping key : Minecraft.getInstance().options.keyMappings) {
                if (key.getName().equals(name)) {
                    return key;
                }
            }
            throw new IllegalArgumentException("unknown key mapping `" + name + "`");
        }
    }
}
