package com.wf.combinedassault.client.hud;

import com.wf.gemrender.ui.Canvas;

/** CA's HUD shapes on the canvas, matching the GuiGraphics primitives they replace. */
public final class CaDraw {
    public static final int GREEN = 0xDD00FF00;
    public static final int RED = 0xDDFF0000;
    public static final int BLUE = 0xDD00FFFF;
    public static final int WHITE = 0xDDFFFFFF;
    public static final int AMBER = 0xDDFFAA00;
    public static final int RELOAD_ARC = 0xCCFF6040;
    public static final int RELOAD_ARC_BG = 0x66162A18;

    private CaDraw() {
    }

    /** Old circle shader: ring centred at {@code r * (1 - t)}, {@code 2 t r} wide; {@code t <= 0} or >= 1 => disc. */
    public static void circle(Canvas c, float x, float y, float r, float t, int argb) {
        if (t <= 0 || t >= 1) {
            c.disc(x, y, r, argb);
        } else {
            c.ring(x, y, r * (1 - t), 2 * t * r, argb);
        }
    }

    /** Arc from 12 o'clock clockwise over [from, to] of a turn; outer radius {@code r}, {@code thickness} inward. */
    public static void arc(Canvas c, float x, float y, float r, float thickness, float from, float to, int argb) {
        if (to <= from) {
            return;
        }
        c.arc(x, y, r - thickness * 0.5f, thickness, (float) (from * Canvas.TAU - Math.PI / 2), (to - from) * Canvas.TAU, argb);
    }

    /** {@code hLine}/{@code vLine}-style outline of a square, inclusive edges like vanilla. */
    public static void square(Canvas c, float x, float y, int size, int argb) {
        int half = size / 2;
        c.frame(x - half, y - half, x + half + 1, y + half + 1, 1, argb);
    }

    public static void squareCorners(Canvas c, float x, float y, int size, int corner, int argb) {
        int half = size / 2;
        float l = x - half;
        float r = x + half;
        float t = y - half;
        float b = y + half;
        c.fill(l, t, l + corner + 1, t + 1, argb);
        c.fill(l, t, l + 1, t + corner + 1, argb);
        c.fill(r - corner, t, r + 1, t + 1, argb);
        c.fill(r, t, r + 1, t + corner + 1, argb);
        c.fill(l, b, l + corner + 1, b + 1, argb);
        c.fill(l, b - corner, l + 1, b + 1, argb);
        c.fill(r - corner, b, r + 1, b + 1, argb);
        c.fill(r, b - corner, r + 1, b + 1, argb);
    }

    /** Diagonal cross: 2x2 centre dot and four arms {@code size/2} long starting 3 out. */
    public static void crossDiagonal(Canvas c, float x, float y, int size, float thickness, int argb) {
        float gap = 3;
        float len = size / 2f;
        c.fill(x - 1, y - 1, x + 1, y + 1, argb);
        float d = (float) Math.sqrt(0.5);
        for (int i = 0; i < 4; i++) {
            float sx = (i & 1) == 0 ? d : -d;
            float sy = (i & 2) == 0 ? d : -d;
            c.line(x + sx * gap, y + sy * gap, x + sx * (gap + len), y + sy * (gap + len), thickness, argb);
        }
    }

    /** Plus rotated 45 degrees, {@code size} across. */
    public static void cross(Canvas c, float x, float y, int size, int argb) {
        float half = size / 2f * (float) Math.sqrt(0.5);
        c.line(x - half, y - half, x + half, y + half, 1, argb);
        c.line(x - half, y + half, x + half, y - half, 1, argb);
    }

    /** Plus with a hollow centre of {@code gap}. */
    public static void crossHollow(Canvas c, float x, float y, float size, float gap, float thickness, int argb) {
        float half = size / 2;
        float g = gap / 2;
        c.line(x - half, y, x - g, y, thickness, argb);
        c.line(x + g, y, x + half, y, thickness, argb);
        c.line(x, y - half, x, y - g, thickness, argb);
        c.line(x, y + g, x, y + half, thickness, argb);
    }

    /** Outline between the corner coordinates, edges inclusive. */
    public static void rectByCorner(Canvas c, float left, float right, float top, float bottom, int argb) {
        c.frame(Math.min(left, right), Math.min(top, bottom), Math.max(left, right) + 1, Math.max(top, bottom) + 1, 1, argb);
    }

    public static int withAlpha(int argb, float alpha) {
        return (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | (argb & 0xFFFFFF);
    }
}
