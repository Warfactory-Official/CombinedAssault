package com.wf.combinedassault.props.client;

import com.wf.combinedassault.screen.Field;
import com.wf.combinedassault.screen.Label;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.Range;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.TextBox;
import com.wf.wflib.ui.api.input.UiSetter;

import java.util.Locale;

import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.stack;

/** Decoration settings / figure box control rows: 38% label, input or range + value box; 36 tall. */
final class SettingRows {
    static final float HEIGHT = 36;
    static final float GAP = 8;

    private SettingRows() {
    }

    static TextBox number() {
        return TextBox.decimal(10, 2, -1.0E6, 1.0E6);
    }

    static TextBox angle() {
        return TextBox.decimal(8, 2, -180, 180);
    }

    static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static float round(double value) {
        return Math.round(value * 100.0) / 100.0f;
    }

    static WidgetDef number(String key, TextBox box, String w) {
        String labelW = "(" + w + " * 0.38)";
        return stack(at("0", "9", Label.plain(Ore.tr(key), Ore.INK_MUTED, 17).clipped(labelW)),
                at(labelW + " + 8", "2", new Field(box, "", w + " - " + labelW + " - 8", 32, 17, Ore.WHITE)));
    }

    /** {@code value} in degrees; range spans -180..180. */
    static WidgetDef angle(String key, Ore.Num value, UiSetter set, Runnable done, TextBox box, String w) {
        String labelW = "(" + w + " * 0.38)";
        return stack(at("0", "9", Label.plain(Ore.tr(key), Ore.INK_MUTED, 17).clipped(labelW)),
                at(labelW + " + 8", "6", new Range(i -> (value.get(i) + 180) / 360, set, done,
                        w + " - " + labelW + " - 92", 24)),
                at(w + " - 76", "3", new Field(box, "", "76", 30, 17, Ore.GOLD)));
    }
}
