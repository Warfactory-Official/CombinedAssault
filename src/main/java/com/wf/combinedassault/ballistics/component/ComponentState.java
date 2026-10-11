package com.wf.combinedassault.ballistics.component;

import java.util.Locale;

/**
 * A component's condition, as a band of its health rather than a number.
 *
 * <p>{@link #BROKEN} = ratio below {@link #DAMAGED}'s floor, not "health==0": a part stops working
 * before stock {@code isDestroyed()} (exactly zero) fires.
 */
public enum ComponentState {

    PRISTINE(0.85f, 1.00f),
    WORN(0.50f, 0.80f),
    DAMAGED(0.15f, 0.45f),
    BROKEN(0.00f, 0.00f);

    public final float minRatio;
    public final float effectiveness;

    ComponentState(float minRatio, float effectiveness) {
        this.minRatio = minRatio;
        this.effectiveness = effectiveness;
    }

    public float stepTarget() {
        return switch (this) {
            case BROKEN -> DAMAGED.minRatio;
            case DAMAGED -> WORN.minRatio;
            case WORN, PRISTINE -> 1.0f;
        };
    }

    public boolean isWorking() {
        return this != BROKEN;
    }

    public String translationKey() {
        return "component.combinedassault.state." + name().toLowerCase(Locale.ROOT);
    }

    public static ComponentState of(float ratio) {
        for (ComponentState state : values()) {
            if (ratio >= state.minRatio) {
                return state;
            }
        }
        return BROKEN;
    }

}
