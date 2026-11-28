package com.wf.combinedassault.screen;

/** GUI-px rect of a {@link ModelPort}'s model area, refreshed per frame; pointer handler for {@link OreScreen}. */
public final class Port {
    /** Buttons 0 (left) / 1 (right); deltas in GUI px. */
    public interface Handler {
        default boolean accepts(int button) {
            return button == 0 || button == 1;
        }

        default void drag(int button, double dx, double dy) {
        }

        default void scroll(double mouseX, double mouseY, double amount) {
        }
    }

    final Handler handler;
    private float left;
    private float top;
    private float right;
    private float bottom;
    boolean shown;

    public Port(Handler handler) {
        this.handler = handler;
    }

    void place(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        shown = true;
    }

    boolean contains(double x, double y) {
        return shown && x >= left && x <= right && y >= top && y <= bottom;
    }

    public float left() {
        return left;
    }

    public float top() {
        return top;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return (left + right) / 2;
    }

    public float centerY() {
        return (top + bottom) / 2;
    }
}
