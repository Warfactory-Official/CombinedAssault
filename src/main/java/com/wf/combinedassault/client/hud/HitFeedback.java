package com.wf.combinedassault.client.hud;

import com.wf.combinedassault.network.message.ServerHitVehicleEvent;

import java.util.ArrayList;
import java.util.List;

/** Client record of the local player's last hits: marker timing and the damage panel's event run. */
public final class HitFeedback {
    public static final List<ServerHitVehicleEvent> events = new ArrayList<>();
    public static long lastHitTime = System.currentTimeMillis();
    static boolean hitVehicle;
    static long hitTimestamp = -1L;
    static long killTimestamp = -1L;

    private HitFeedback() {
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
