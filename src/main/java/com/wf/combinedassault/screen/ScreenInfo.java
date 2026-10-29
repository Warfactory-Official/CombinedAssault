package com.wf.combinedassault.screen;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Binding of a model screen ({@code screen_<id>} texture slot: pose, size and bone come from its mesh) to a layout.
 */
public class ScreenInfo {

    public static final String SLOT_PREFIX = "screen_";

    @SerializedName("layout")
    public ResourceLocation layout;

    /** Weapon part the screen reports on; null => first weapon part. */
    @SerializedName("part")
    @Nullable
    public String part;

    @SerializedName("max_fps")
    public int maxFps = 20;

    /** Units across when the layout declares no design size. */
    @SerializedName("units_width")
    public float unitsWidth = 192;

    public void validate(String id) {
        if (layout == null || maxFps <= 0 || !(unitsWidth > 0)) {
            throw new IllegalArgumentException("screen " + id + " needs layout, positive max_fps and units_width");
        }
    }
}
