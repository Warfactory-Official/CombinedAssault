package com.wf.combinedassault.indirect;

import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/** Rounds ordered on a point; client state of the station's operator. {@code round} AIR = whatever is loaded. */
public final class FireMission {

    private final Vec3 target;
    private final Item round;
    private int remaining;

    public FireMission(Vec3 target, Item round, int rounds) {
        this.target = target;
        this.round = round;
        this.remaining = rounds;
    }

    public Vec3 target() {
        return target;
    }

    public Item round() {
        return round;
    }

    public int remaining() {
        return remaining;
    }

    /** @return true when the last round has gone */
    public boolean fired() {
        return --remaining <= 0;
    }
}
