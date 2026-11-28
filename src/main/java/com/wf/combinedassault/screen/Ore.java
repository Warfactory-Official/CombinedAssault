package com.wf.combinedassault.screen;

import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.widget.Place;
import com.wf.wflib.ui.api.widget.Point;
import com.wf.wflib.ui.api.widget.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * Ore look (ApricityUI Ore theme + CA overrides) for CA screens on wfUI. Layout unit = CSS px at 1080 window height,
 * GUI scale ignored (Apricity browser viewport parity). {@link #W} / {@link #H} = screen in units.
 */
public final class Ore {
    public static final float REFERENCE = 1080;
    public static final String W = "(ui.width * 1080 / ui.height)";
    public static final String H = "1080";

    public static final ResourceLocation DISPLAY_FONT = CombinedAssault.modLocation("ore_display");
    public static final Style DISPLAY = Style.EMPTY.withFont(DISPLAY_FONT);
    public static final Style BOLD = Style.EMPTY.withBold(true);

    public static final int INK = 0xFFF4F5F7;
    public static final int INK_MUTED = 0xFFB6BAC1;
    public static final int INK_DARK = 0xFF191A1C;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int EDGE = 0xFF1E1E1F;
    public static final int EDGE_LIGHT = 0xFF77797C;
    public static final int EDGE_DARK = 0xFF292A2B;
    public static final int GOLD = 0xFFF0B92D;
    public static final int BLUE = 0xFF2D78A8;
    public static final int PURPLE = 0xFF7345E5;
    public static final int GREEN = 0xFF3C8527;

    public static final int SURFACE = 0xCC48494A;
    public static final int SURFACE_DEEP = 0xD1313233;
    public static final int NAV = 0xD6E6E8EB;
    public static final int NAV_SHADOW = 0xFFB1B2B5;
    public static final int NAV_EDGE = 0xFF151516;
    public static final int FIELD_SHADOW = 0xFF242425;
    public static final int ROW = 0xCC363739;
    public static final int ROW_HOVER = 0xE04A4B4E;
    public static final int ROW_HOVER_EDGE = 0xFF85878B;
    public static final int ROW_ACTIVE = 0xDB514A35;
    public static final int LIST_ITEM = SURFACE;
    public static final int LIST_ITEM_HOVER = 0xE056585B;
    public static final int INSET = 0xC2343537;
    public static final int VIEWPORT = 0x59303133;
    public static final int VIEWPORT_EDGE = 0xFF56585B;
    public static final int VIEWPORT_HI = 0xFF222325;
    public static final int VIEWPORT_LO = 0xFF444649;
    public static final int AXIS = 0xB83E4043;
    public static final int TRACK = 0xFFDDD6FE;

    /** Button fills: base, hover, bottom shadow, highlight. */
    public enum Tone {
        GREEN(0xFF3C8527, 0xFF2A641C, 0xFF1D4D13, 0x38FFFFFF),
        PURPLE(0xFF7345E5, 0xFF5D2CC6, 0xFF4A1CAC, 0x40FFFFFF),
        RED(0xFFB33B31, 0xFF8B2923, 0xFF662019, 0x33FFFFFF);

        final int base;
        final int hover;
        final int shadow;
        final int hi;

        Tone(int base, int hover, int shadow, int hi) {
            this.base = base;
            this.hover = hover;
            this.shadow = shadow;
            this.hi = hi;
        }
    }

    /** Value of the innermost repeat item ({@code null} outside a repeat). */
    @FunctionalInterface
    public interface Str {
        String get(Object item);
    }

    @FunctionalInterface
    public interface Flag {
        boolean get(Object item);
    }

    @FunctionalInterface
    public interface Num {
        float get(Object item);
    }

    private Ore() {
    }

    /** Translation of {@code key}, re-resolved on language change only. */
    public static Str tr(String key) {
        return new Str() {
            private String language;
            private String text;

            @Override
            public String get(Object item) {
                String now = Minecraft.getInstance().getLanguageManager().getSelected();
                if (!now.equals(language)) {
                    language = now;
                    text = Component.translatable(key).getString();
                }
                return text;
            }
        };
    }

    /** Full-screen layout in Ore units. */
    public static UiLayout layout(String name, WidgetDef root) {
        return new UiLayout(CombinedAssault.modLocation("screen/" + name),
                new Transform("0", "0", "0", "ui.height / " + REFERENCE, Point.TOP_LEFT, root)).absolute();
    }

    /** Muted 12 px note centred at the top of a body {@code w} wide (CSS {@code .empty-state}). */
    public static WidgetDef note(String key, String w) {
        return new Place("(" + w + ") / 2", "9", Point.TOP, Label.plain(tr(key), INK_MUTED, 12));
    }

    public static String f(float v) {
        return Float.toString(v);
    }

    /** Share of {@code total} units: {@code pct(W, 0.25f)}. */
    public static String pct(String total, float share) {
        return "(" + total + " * " + share + ")";
    }
}
