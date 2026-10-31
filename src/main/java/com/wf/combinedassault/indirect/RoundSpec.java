package com.wf.combinedassault.indirect;

import com.wf.combinedassault.dynamics.PhysicsEngine;

/**
 * Not an entity/weapon/registry object: decouples solver from world, lets WFLib feed its own round to it.
 *
 * @param muzzleSpeed   b/t leaving the barrel
 * @param drag          fraction of speed lost per tick
 * @param gravity       b/t^2, normally {@link PhysicsEngine#G}
 * @param lifeTicks     ticks before the round self-deletes in flight
 * @param quadraticDrag {@code v -= k|v|v} per tick on top of {@code drag}; > 0 => stepped, no closed form
 */
public record RoundSpec(double muzzleSpeed, double drag, double gravity, int lifeTicks, double quadraticDrag) {

    public RoundSpec(double muzzleSpeed, double drag, double gravity, int lifeTicks) {
        this(muzzleSpeed, drag, gravity, lifeTicks, 0.0);
    }

    public double decay() {
        return Trajectory.decayOf(drag);
    }

    /** Retained fraction at {@code speed}; WFLib {@code KineticPreset.decay(double)}. */
    public double decay(double speed) {
        return quadraticDrag == 0.0 ? decay() : Math.max(decay() - quadraticDrag * speed, 0.0);
    }

    public RoundSpec withMuzzleSpeed(double speed) {
        return new RoundSpec(speed, drag, gravity, lifeTicks, quadraticDrag);
    }

}
