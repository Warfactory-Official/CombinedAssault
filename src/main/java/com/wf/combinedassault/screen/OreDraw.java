package com.wf.combinedassault.screen;

import com.wf.gemrender.ui.Canvas;

/** Ore chrome primitives; CSS box-shadow insets as strips. */
final class OreDraw {
    private OreDraw() {
    }

    static void fill(Canvas c, float x, float y, float w, float h, int argb) {
        if ((argb >>> 24) != 0 && w > 0 && h > 0) {
            c.fill(x, y, x + w, y + h, argb);
        }
    }

    /** {@code t}-wide border inside the box. */
    static void frame(Canvas c, float x, float y, float w, float h, float t, int argb) {
        if ((argb >>> 24) != 0 && t > 0) {
            c.frame(x, y, x + w, y + h, t, argb);
        }
    }

    /** Inset top/left strip {@code t} wide. */
    static void insetTopLeft(Canvas c, float x, float y, float w, float h, float t, int argb) {
        fill(c, x, y, w, t, argb);
        fill(c, x, y + t, t, h - t, argb);
    }

    /** Inset bottom/right strip {@code t} wide. */
    static void insetBottomRight(Canvas c, float x, float y, float w, float h, float t, int argb) {
        fill(c, x, y + h - t, w, t, argb);
        fill(c, x + w - t, y, t, h - t, argb);
    }

    /** Sunken viewport: edge 2, translucent fill, inset 3 dark top-left / light bottom-right. */
    static void viewport(Canvas c, float x, float y, float w, float h) {
        fill(c, x + 2, y + 2, w - 4, h - 4, Ore.VIEWPORT);
        insetTopLeft(c, x + 2, y + 2, w - 4, h - 4, 3, Ore.VIEWPORT_HI);
        insetBottomRight(c, x + 2, y + 2, w - 4, h - 4, 3, Ore.VIEWPORT_LO);
        frame(c, x, y, w, h, 2, Ore.VIEWPORT_EDGE);
    }
}
