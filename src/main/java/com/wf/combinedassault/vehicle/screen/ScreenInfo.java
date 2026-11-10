package com.wf.combinedassault.vehicle.screen;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Binding of a model screen ({@code screen_<id>} texture slot: pose, size and bone come from its mesh) to a layout,
 * or to {@code pages} cycled by the screen's viewer.
 */
public class ScreenInfo {

    public static final String SLOT_PREFIX = "screen_";

    public enum Kind {
        /** {@code computer} subject of a weapon part. */
        @SerializedName("gun_computer") GUN_COMPUTER,
        /** {@code dash} subject: speed, rpm, gear, fuel, warning lights. */
        @SerializedName("dash") DASH,
        /** Live picture of a weapon part's sight, {@code computer} subject layout drawn over it (reticle, OSD). */
        @SerializedName("sight") SIGHT,
        /** Aircraft display: {@code mfd} subject plus the HUD subjects of the vehicle and its sight station. */
        @SerializedName("mfd") MFD
    }

    @SerializedName("layout")
    public ResourceLocation layout;

    @SerializedName("kind")
    public Kind kind = Kind.GUN_COMPUTER;

    /** {@link Kind#GUN_COMPUTER} / {@link Kind#SIGHT}: weapon part the screen reports on; null => first weapon part. */
    @SerializedName("part")
    @Nullable
    public String part;

    @SerializedName("max_fps")
    public int maxFps = 20;

    /** Units across when the layout declares no design size. */
    @SerializedName("units_width")
    public float unitsWidth = 192;

    /**
     * {@link Kind#SIGHT}: lens this far ahead of the sight socket along the view, blocks. Clears the vehicle's own model
     * (Flywheel-drawn => the pass cannot hide it); aircraft sight socket = pilot eye => about a nose length.
     */
    @SerializedName("objective")
    public float objective = 0.25f;

    /** {@link Kind#SIGHT}: seat of {@code part} toggles screen <-> SCOPE on the magnification key (tank gunner). */
    @SerializedName("scope_toggle")
    public boolean scopeToggle = true;

    /** Pages in cycle order; null => this entry is the only page. */
    @SerializedName("pages")
    @Nullable
    public List<ScreenInfo> pages;

    public List<ScreenInfo> pages() {
        return pages == null ? List.of(this) : pages;
    }

    public void validate(String id) {
        if (pages != null) {
            if (pages.isEmpty() || layout != null) {
                throw new IllegalArgumentException("screen " + id + ": `pages` non-empty, no own layout");
            }
            for (ScreenInfo page : pages) {
                if (page.pages != null) {
                    throw new IllegalArgumentException("screen " + id + ": nested `pages`");
                }
                page.validate(id);
            }
            return;
        }
        if (layout == null || maxFps <= 0 || !(unitsWidth > 0)) {
            throw new IllegalArgumentException("screen " + id + " needs layout, positive max_fps and units_width");
        }
    }
}
