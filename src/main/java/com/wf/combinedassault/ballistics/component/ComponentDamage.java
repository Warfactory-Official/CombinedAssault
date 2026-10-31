package com.wf.combinedassault.ballistics.component;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.part.data.PartUnitData;
import com.wf.combinedassault.vehicle.part.PartUnit;

/** Part kind, damage band, effectiveness: computed from health each call, never cached. */
public final class ComponentDamage {

    private ComponentDamage() {
    }

    public static ComponentKind kind(PartUnit<?> part) {
        PartUnitData data = part.getData();
        ComponentKind declared = data == null ? null : ComponentKind.byName(data.getComponent());
        return declared != null ? declared : ComponentKind.infer(part);
    }

    public static ComponentState state(PartUnit<?> part) {
        if (part.isDetached()) {
            return ComponentState.BROKEN;
        }
        float max = part.getMaxHealth();
        return max <= 0 ? ComponentState.PRISTINE : ComponentState.of(Mth.clamp(part.getHealth() / max, 0, 1));
    }

    /** Effectiveness 0-1: interpolated within band, not stepped; band names describe, don't simulate. */
    public static float effectiveness(PartUnit<?> part) {
        if (part.isDetached() || part.isDestroyed()) {
            return 0;
        }
        float max = part.getMaxHealth();
        if (max <= 0) {
            return 1;
        }
        float ratio = Mth.clamp(part.getHealth() / max, 0, 1);
        ComponentState state = ComponentState.of(ratio);
        if (state == ComponentState.BROKEN) {
            // BROKEN=>0 flat; must match ComponentState.isWorking(): fire-refusal gates read state, turret-slew gates read this.
            return 0;
        }
        ComponentState above = above(state);
        if (above == null) {
            return state.effectiveness;
        }
        float span = above.minRatio - state.minRatio;
        float t = span <= 0 ? 1 : (ratio - state.minRatio) / span;
        return Mth.lerp(Mth.clamp(t, 0, 1), state.effectiveness, above.effectiveness);
    }

    @Nullable
    private static ComponentState above(ComponentState state) {
        return switch (state) {
            case BROKEN -> ComponentState.DAMAGED;
            case DAMAGED -> ComponentState.WORN;
            case WORN -> ComponentState.PRISTINE;
            case PRISTINE -> null;
        };
    }

}
