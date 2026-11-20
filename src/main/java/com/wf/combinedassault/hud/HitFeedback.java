package com.wf.combinedassault.hud;

import com.wf.combinedassault.ballistics.message.ServerHitVehicleEvent;

import java.util.ArrayList;
import java.util.List;

/** Client record of the local player's last hits: marker timing and the damage panel's event run. */
public final class HitFeedback {
    public static final List<ServerHitVehicleEvent> events = new ArrayList<>();
    /** Last resolved rounds with detail, newest last, for the F3 hit lines; outlives {@link #events}. */
    public static final List<ServerHitVehicleEvent> recent = new ArrayList<>();
    private static final int RECENT = 8;
    private static long lastHitTime = System.currentTimeMillis();
    static boolean hitVehicle;
    static long hitTimestamp = -1L;
    static long killTimestamp = -1L;

    private HitFeedback() {
    }

    public static void remember(ServerHitVehicleEvent event) {
        recent.add(event);
        while (recent.size() > RECENT) {
            recent.remove(0);
        }
    }

    public static long lastHitTime() {
        return lastHitTime;
    }

    public static void markHit() {
        lastHitTime = System.currentTimeMillis();
    }

    public static long hitTimestamp() {
        return hitTimestamp;
    }

    public static long killTimestamp() {
        return killTimestamp;
    }

    public static boolean hitVehicle() {
        return hitVehicle;
    }

    public static void markHitTimestamp(boolean vehicle, boolean kill) {
        if (kill) {
            killTimestamp = System.currentTimeMillis();
        } else {
            hitTimestamp = System.currentTimeMillis();
        }
        hitVehicle = vehicle;
    }
}
