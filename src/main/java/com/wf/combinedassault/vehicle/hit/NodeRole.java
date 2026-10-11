package com.wf.combinedassault.vehicle.hit;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** glTF {@code extras.combinedassaultRole}. Node names: authoring / diagnostics only, never a role. */
public enum NodeRole {
    BODY(Kind.FRAME),
    PART(Kind.FRAME),
    PITCH(Kind.FRAME),
    COLLIDER(Kind.SHAPE),
    HIT(Kind.SHAPE),
    HIT_SURFACE(Kind.SHAPE),
    SEAT(Kind.SOCKET),
    DISMOUNT(Kind.SOCKET),
    VIEW(Kind.SOCKET),
    SIGHT(Kind.SOCKET),
    BOLT(Kind.SOCKET),
    ERA(Kind.SOCKET),
    SLAT(Kind.SOCKET),
    MOUNT_POINT(Kind.SOCKET),
    HITCH(Kind.SOCKET),
    TOWABLE(Kind.SOCKET),
    AXLE(Kind.SOCKET),
    EXHAUST(Kind.SOCKET),
    VORTEX(Kind.SOCKET),
    SMOKE(Kind.SOCKET),
    AFTERBURNER(Kind.SOCKET),
    CAMERA(Kind.SOCKET),
    CAMERA_ZOOMED(Kind.SOCKET),
    LIGHT(Kind.SOCKET),
    CARGO_BAY(Kind.SOCKET),
    CENTER_OF_MASS(Kind.SOCKET),
    PIVOT(Kind.SOCKET),
    TRACK(Kind.SOCKET),
    PROPELLER(Kind.SOCKET);

    public enum Kind {
        FRAME, SHAPE, SOCKET
    }

    public final Kind kind;

    NodeRole(Kind kind) {
        this.kind = kind;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "" => null; unknown => throws. */
    @Nullable
    public static NodeRole of(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (NodeRole role : values()) {
            if (role.key().equals(key)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown node role '" + key + "'");
    }
}
