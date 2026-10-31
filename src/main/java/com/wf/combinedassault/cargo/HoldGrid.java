package com.wf.combinedassault.cargo;

/** Cargo hold panel layout: columns/rows/geometry sized to the hold's slot count + window, not a fixed 54-slot chest. Shared by {@link VehicleStorageMenu} (slots) and the screen (render). */
public final class HoldGrid {

    public static final int SLOT = 18;

    /** = player inventory width. */
    public static final int STRIDE = 9;

    public static final int PAD = 8;

    public static final int TITLE = 18;

    public static final int BAR = 8;

    private static final int BAR_GAP = 3;

    /** Clearance above the player inventory; includes its label line. */
    private static final int GAP = 14;

    private static final int PLAYER = 3 * SLOT + 4 + SLOT;

    private static final int BOTTOM = 8;

    /** Headroom so a full-height panel doesn't read as clipped against the window edge. */
    private static final int MARGIN = 16;

    /** Minecraft's minimum scaled GUI size; real layout replaces this once the screen measures the window. */
    private static final int FLOOR_WIDTH = 320;
    private static final int FLOOR_HEIGHT = 240;

    private final int slots;
    private final int columns;
    private final int rows;
    private final int windowRows;

    private HoldGrid(int slots, int columns, int rows, int windowRows) {
        this.slots = slots;
        this.columns = columns;
        this.rows = rows;
        this.windowRows = windowRows;
    }

    public static HoldGrid fit(int slots, int screenWidth, int screenHeight) {
        int held = Math.max(0, slots);
        int fits = Math.max(1, (screenHeight - MARGIN - TITLE - GAP - PLAYER - BOTTOM) / SLOT);
        int columns = Math.min(STRIDE, Math.max(1, held));
        // Widen by STRIDE while rows overflow + window has room; multiples of STRIDE align with the player grid below.
        while (columns >= STRIDE && rowsFor(held, columns) > fits
                && widthFor(columns + STRIDE, true) <= screenWidth - MARGIN) {
            columns += STRIDE;
        }
        int rows = rowsFor(held, columns);
        return new HoldGrid(held, columns, rows, Math.min(rows, fits));
    }

    /** Before the screen measures the real window; see {@link #FLOOR_WIDTH}. */
    public static HoldGrid unmeasured(int slots) {
        return fit(slots, FLOOR_WIDTH, FLOOR_HEIGHT);
    }

    public int slots() {
        return slots;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int windowRows() {
        return windowRows;
    }

    public boolean scrollable() {
        return rows > windowRows;
    }

    public int maxScroll() {
        return Math.max(0, rows - windowRows);
    }

    public int width() {
        return widthFor(columns, scrollable());
    }

    public int height() {
        return TITLE + windowRows * SLOT + GAP + PLAYER + BOTTOM;
    }

    public int gridLeft() {
        return PAD + (content() - columns * SLOT) / 2;
    }

    public int gridTop() {
        return TITLE;
    }

    public int windowHeight() {
        return windowRows * SLOT;
    }

    public int gridBottom() {
        return TITLE + windowRows * SLOT;
    }

    public int playerLeft() {
        return PAD + (content() - STRIDE * SLOT) / 2;
    }

    public int playerTop() {
        return TITLE + windowRows * SLOT + GAP;
    }

    public int hotbarTop() {
        return playerTop() + 3 * SLOT + 4;
    }

    public int barLeft() {
        return PAD + content() + BAR_GAP;
    }

    /** >= {@link #STRIDE} width: a narrow hold still aligns with the full player row below. */
    private int content() {
        return Math.max(columns, STRIDE) * SLOT;
    }

    private static int widthFor(int columns, boolean bar) {
        return PAD + Math.max(columns, STRIDE) * SLOT + (bar ? BAR_GAP + BAR : 0) + PAD;
    }

    private static int rowsFor(int slots, int columns) {
        return (slots + columns - 1) / columns;
    }

}
