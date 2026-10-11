package com.wf.combinedassault.ballistics.component;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Display model node name -> system; shared by damage tiles and hit geometry. */
public final class NodeSystems {

    private NodeSystems() {
    }

    public enum Match {
        NONE, KIND, RUNNING_GEAR
    }

    /** {@link Match#KIND} => {@link #kind} non-null. */
    public static Match match(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        ComponentKind kind = kind(name);
        if (kind == ComponentKind.TURRET || kind == ComponentKind.BARREL) {
            return Match.KIND;
        }
        if (name.contains("wheel") || name.contains("track")) {
            return Match.RUNNING_GEAR;
        }
        return kind != null ? Match.KIND : Match.NONE;
    }

    /** Ignores running gear; see {@link #match}. */
    @Nullable
    public static ComponentKind kind(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (name.contains("turret")) {
            return ComponentKind.TURRET;
        }
        if (name.contains("cannon") || name.contains("barrel") || name.contains("gun")
                || name.equals("mg") || name.startsWith("mg_")) {
            return ComponentKind.BARREL;
        }
        if (name.contains("tail") && name.contains("rotor")) {
            return ComponentKind.TAIL_ROTOR;
        }
        if (name.contains("rotor") || name.contains("blade")) {
            return ComponentKind.ROTOR;
        }
        if (name.contains("wing")) {
            return ComponentKind.WING;
        }
        return null;
    }

    /** {@code *left*} or an {@code l} token ({@code WHEEL_00_L}); right likewise. */
    public static VehicleSystems.Side side(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (name.contains("left")) {
            return VehicleSystems.Side.LEFT;
        }
        if (name.contains("right")) {
            return VehicleSystems.Side.RIGHT;
        }
        for (String token : name.split("[^a-z0-9]+")) {
            if (token.equals("l")) {
                return VehicleSystems.Side.LEFT;
            }
            if (token.equals("r")) {
                return VehicleSystems.Side.RIGHT;
            }
        }
        return VehicleSystems.Side.NONE;
    }
}
